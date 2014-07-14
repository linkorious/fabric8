/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.autoscale;

import io.fabric8.api.AutoScaleRequest;
import io.fabric8.api.Container;
import io.fabric8.api.ContainerAutoScaler;
import io.fabric8.api.Containers;
import io.fabric8.api.DataStore;
import io.fabric8.api.FabricRequirements;
import io.fabric8.api.FabricService;
import io.fabric8.api.ProfileRequirements;
import io.fabric8.api.jcip.GuardedBy;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.common.util.Closeables;
import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.groups.internal.ZooKeeperGroup;
import io.fabric8.zookeeper.ZkPath;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Fabric auto-scaler which when it becomes the master auto-scales
 * profiles according to their requirements defined via
 * {@link FabricService#setRequirements(io.fabric8.api.FabricRequirements)}
 */
@ThreadSafe
@Component(name = "io.fabric8.autoscale", label = "Fabric8 auto scaler", immediate = true,
        policy = ConfigurationPolicy.OPTIONAL, metatype = true)
public final class AutoScaleController extends AbstractComponent implements GroupListener<AutoScalerNode> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoScaleController.class);

    @Reference(referenceInterface = CuratorFramework.class, bind = "bindCurator", unbind = "unbindCurator")
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = FabricService.class, bind = "bindFabricService", unbind = "unbindFabricService")
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();

    @Property(name = "pollTime", longValue = 10000,
            label = "Poll period",
            description = "The number of milliseconds between polls to check if the system still has its requirements satisfied.")
    private long pollTime = 10000;

    private AtomicReference<Timer> timer = new AtomicReference<Timer>();

    @GuardedBy("volatile")
    private volatile Group<AutoScalerNode> group;

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            onConfigurationChanged();
        }
    };

    @Activate
    void activate() {
        group = new ZooKeeperGroup<AutoScalerNode>(curator.get(), ZkPath.AUTO_SCALE.getPath(), AutoScalerNode.class);
        group.add(this);
        group.update(createState());
        group.start();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        disableTimer();
        deactivateComponent();
        group.remove(this);
        Closeables.closeQuitely(group);
        group = null;
    }

    @Override
    public void groupEvent(Group<AutoScalerNode> group, GroupEvent event) {
        DataStore dataStore = fabricService.get().getDataStore();
        switch (event) {
            case CONNECTED:
            case CHANGED:
                if (isValid()) {
                    AutoScalerNode state = createState();
                    try {
                        if (group.isMaster()) {
                            LOGGER.info("AutoScaleController is the master");
                            group.update(state);
                            dataStore.trackConfiguration(runnable);
                            enableTimer();
                            onConfigurationChanged();
                        } else {
                            LOGGER.info("AutoScaleController is not the master");
                            group.update(state);
                            disableTimer();
                            dataStore.untrackConfiguration(runnable);
                        }
                    } catch (IllegalStateException e) {
                        // Ignore
                    }
                } else {
                    LOGGER.info("Not valid with master: " + group.isMaster()
                            + " fabric: " + fabricService.get()
                            + " curator: " + curator.get());
                }
                break;
            case DISCONNECTED:
                dataStore.untrackConfiguration(runnable);
        }
    }

    protected void enableTimer() {
        Timer newTimer = new Timer("fabric8-autoscaler");
        if (timer.compareAndSet(null, newTimer)) {
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    LOGGER.debug("autoscale timer");
                    autoScale();
                }
            };
            newTimer.schedule(timerTask, pollTime, pollTime);
        }
    }

    protected void disableTimer() {
        Timer oldValue = timer.getAndSet(null);
        if (oldValue != null) {
            oldValue.cancel();
        }
    }


    private void onConfigurationChanged() {
        LOGGER.debug("Configuration has changed; so checking the auto-scaling requirements");
        autoScale();
    }

    private void autoScale() {
        FabricRequirements requirements = fabricService.get().getRequirements();
        List<ProfileRequirements> profileRequirements = requirements.getProfileRequirements();
        for (ProfileRequirements profileRequirement : profileRequirements) {
            ContainerAutoScaler autoScaler = createAutoScaler(requirements, profileRequirement);
            if (autoScaler != null) {
                autoScaleProfile(autoScaler, requirements, profileRequirement);
            } else {
                LOGGER.warn("No ContainerAutoScaler available for profile " + profileRequirement.getProfile());
            }
        }
    }

    private ContainerAutoScaler createAutoScaler(FabricRequirements requirements, ProfileRequirements profileRequirements) {
        FabricService service = fabricService.getOptional();
        if (service != null) {
            return service.createContainerAutoScaler(requirements, profileRequirements);
        } else {
            LOGGER.warn("No FabricService available so cannot autoscale");
            return null;
        }
    }

    private void autoScaleProfile(ContainerAutoScaler autoScaler, FabricRequirements requirements, ProfileRequirements profileRequirement) {
        String profile = profileRequirement.getProfile();
        Integer minimumInstances = profileRequirement.getMinimumInstances();
        if (minimumInstances != null) {
            // lets check if we need to provision more
            List<Container> containers = containersForProfile(profile);
            int count = containers.size();
            int delta = minimumInstances - count;
            try {
                if (delta < 0) {
                    autoScaler.destroyContainers(profile, -delta, containers);
                } else if (delta > 0) {
                    if (requirementsSatisfied(requirements, profileRequirement)) {
                        // TODO should we figure out the version from the requirements?
                        FabricService service = fabricService.get();
                        String version = service.getDefaultVersion().getId();
                        AutoScaleRequest command = new AutoScaleRequest(service, version, profile, delta, requirements, profileRequirement);
                        autoScaler.createContainers(command);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to auto-scale " + profile + ". Caught: " + e, e);
            }
        }
    }

    private boolean requirementsSatisfied(FabricRequirements requirements, ProfileRequirements profileRequirement) {
        List<String> dependentProfiles = profileRequirement.getDependentProfiles();
        if (dependentProfiles != null) {
            for (String dependentProfile : dependentProfiles) {
                ProfileRequirements dependentProfileRequirements = requirements.getOrCreateProfileRequirement(dependentProfile);
                Integer minimumInstances = dependentProfileRequirements.getMinimumInstances();
                if (minimumInstances != null) {
                    List<Container> containers = containersForProfile(dependentProfile);
                    int dependentSize = containers.size();
                    if (minimumInstances > dependentSize) {
                        LOGGER.info("Cannot yet auto-scale profile " + profileRequirement.getProfile()
                                + " since dependent profile " + dependentProfile + " has only " + dependentSize
                                + " container(s) when it requires " + minimumInstances + " container(s)");
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Returns all the current alive profiles for the given profile
     */
    private List<Container> containersForProfile(String profile) {
        List<Container> answer = new ArrayList<Container>();
        List<Container> containers = Containers.containersForProfile(fabricService.get().getContainers(), profile);
        for (Container container : containers) {
            boolean alive = container.isAlive();
            boolean provisioningPending = container.isProvisioningPending();
            if (alive || provisioningPending) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Container " + container.getId() + " is alive " + alive + " provision is pending " + provisioningPending);
                }
                answer.add(container);
            }
        }
        return answer;
    }

    private AutoScalerNode createState() {
        AutoScalerNode state = new AutoScalerNode();
        return state;
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }
}

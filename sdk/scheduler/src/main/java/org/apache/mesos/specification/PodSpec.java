package org.apache.mesos.specification;

import org.apache.mesos.offer.constrain.PlacementRule;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Created by gabriel on 11/7/16.
 */
public interface PodSpec {
    String getType();
    Optional<String> getUser();
    Integer getIndex();
    List<TaskSpec> getTasks();
    Collection<ResourceSet> getResources();
    Optional<PlacementRule> getPlacementRule();
}
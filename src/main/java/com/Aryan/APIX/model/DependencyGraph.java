package com.Aryan.APIX.model;


import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DependencyGraph {
    private List<DependencyNode> nodes;
    private List<DependencyEdge> edges;
}

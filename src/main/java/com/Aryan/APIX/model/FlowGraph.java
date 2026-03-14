package com.Aryan.APIX.model;


import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FlowGraph {

    private List<FlowNode> nodes;
    private List<FlowEdge> edges;
}

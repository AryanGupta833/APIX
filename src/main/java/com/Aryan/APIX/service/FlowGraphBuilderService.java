package com.Aryan.APIX.service;

import com.Aryan.APIX.model.FlowEdge;
import com.Aryan.APIX.model.FlowGraph;
import com.Aryan.APIX.model.FlowNode;
import com.Aryan.APIX.model.FlowTrace;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FlowGraphBuilderService {

    public FlowGraph build(FlowTrace trace){

        List<FlowNode> nodes = new ArrayList<>();
        List<FlowEdge> edges = new ArrayList<>();
        nodes.add(node("client", "Client", 0));
        nodes.add(node("connection", "Connection Phase", trace.getConnectionTime()));
        nodes.add(node("execution", "Execution Phase", trace.getExecutionTime()));
        nodes.add(node("response", "Response Phase", trace.getResponseTime()));

        edges.add(edge("client", "connection"));
        edges.add(edge("connection", "execution"));
        edges.add(edge("execution", "response"));

        FlowGraph graph = new FlowGraph();
        graph.setNodes(nodes);
        graph.setEdges(edges);

        return graph;
    }

    private FlowNode node(String id, String label, long duration){
        FlowNode n = new FlowNode();
        n.setId(id);
        n.setLabel(label);
        n.setDuration(duration);
        return n;
    }

    private FlowEdge edge(String from, String to){
        FlowEdge e = new FlowEdge();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }
}
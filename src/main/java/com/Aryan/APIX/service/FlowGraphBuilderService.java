package com.Aryan.APIX.service;

import com.Aryan.APIX.model.FlowEdge;
import com.Aryan.APIX.model.FlowGraph;
import com.Aryan.APIX.model.FlowNode;
import com.Aryan.APIX.model.FlowTrace;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FlowGraphBuilderService {
    public FlowGraph build(FlowTrace trace){
        List<FlowNode> nodes=new ArrayList<>();
        List<FlowEdge> edges=new ArrayList<>();

        nodes.add(node("client","Client",0));
        nodes.add(node("dns","DNS Lookup",trace.getDnsTime()));
        nodes.add(node("tcp","TCP Connect",trace.getTcpConnectTime()));
        nodes.add(node("tls","TLS Handshake",trace.getTlsHandshakeTime()));
        nodes.add(node("server","Server Processing",trace.getServerProcessingTime()));
        nodes.add(node("transfer","Response Transfer",trace.getResponseTransferTime()));

        edges.add(edge("client","dns"));
        edges.add(edge("dns","tcp"));
        edges.add(edge("tcp","tls"));
        edges.add(edge("tls","server"));
        edges.add(edge("server","transfer"));

        FlowGraph graph=new FlowGraph();
        graph.setNodes(nodes);
        graph.setEdges(edges);

        return graph;
    }

    private FlowNode node(String id,String label,long duration){
        FlowNode n=new FlowNode();
        n.setId(id);
        n.setLabel(label);
        n.setDuration(duration);

        return n;
    }

    private FlowEdge edge(String from,String to){
        FlowEdge e=new FlowEdge();
        e.setFrom(from);
        e.setTo(to);

        return e;
    }
}

package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.model.DependencyEdge;
import com.Aryan.APIX.model.DependencyGraph;
import com.Aryan.APIX.model.DependencyNode;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DependencyGraphBuilderService {

    public DependencyGraph build(List<RequestHistory> history){

        Map<String, DependencyNode> nodes = new HashMap<>();
        List<DependencyEdge> edges = new ArrayList<>();

        String client = "APIX Client";

        nodes.putIfAbsent(client, node(client,"APIX"));

        String prevHost = client;

        for(RequestHistory exec : history){

            String host = extractService(exec.getUrl());

            nodes.putIfAbsent(host, node(host, exec.getUrl()));
            final String fromHost = prevHost;
            boolean exists = edges.stream()
                    .anyMatch(e -> e.getFromService().equals(fromHost)
                            && e.getToService().equals(host));

            if(!exists){
                edges.add(edge(prevHost, host));
            }

            prevHost = host;
        }

        DependencyGraph graph = new DependencyGraph();
        graph.setNodes(new ArrayList<>(nodes.values()));
        graph.setEdges(edges);

        return graph;
    }

    private DependencyNode node(String service,String endpoint){
        DependencyNode n=new DependencyNode();
        n.setServiceName(service);
        n.setEndpoint(endpoint);
        return n;
    }
    private DependencyEdge edge(String from,String to){
        DependencyEdge e=new DependencyEdge();
        e.setFromService(from);
        e.setToService(to);

        return e;
    }

    private String extractService(String url){
        try{
            URI uri=new URI(url);
            return uri.getHost();
        }
        catch (Exception e){
            return "unknown-service";
        }
    }
}

package com.Aryan.APIX.controller;


import com.Aryan.APIX.entity.Collection;
import com.Aryan.APIX.entity.CollectionRequest;
import com.Aryan.APIX.service.CollectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/apix/collections")
public class CollectionController {

    @Autowired
    private CollectionService service;

    @PostMapping
    public Collection createCollection(@RequestParam String name){
        return service.createCollection(name);
    }

    @GetMapping
    public List<Collection>getCollections(){
        return service.getCollection();
    }

    @PostMapping("/{collectionId}/requests")
    public CollectionRequest saveRequest(@PathVariable Long collectionId,@RequestBody CollectionRequest request){
        return service.saveRequest(collectionId, request);
    }
    @GetMapping("/{id}")
    public List<CollectionRequest> getRequests(@PathVariable Long id){
        return service.getRequests(id);
    }
}

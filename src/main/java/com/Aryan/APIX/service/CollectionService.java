package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.Collection;
import com.Aryan.APIX.entity.CollectionRequest;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.repository.CollectionRepository;
import com.Aryan.APIX.repository.CollectionRequestRepository;
import com.Aryan.APIX.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CollectionService {

    @Autowired
    private CollectionRepository collectionRepository;

    @Autowired
    private CollectionRequestRepository collectionRequestRepository;

    @Autowired
    private UserRepository userRepository;

    public Collection createCollection(String name){
        Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String email;
        if(principal instanceof String){
            email=(String) principal;
        }
        else if(principal instanceof org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser oidcUser){
            email=oidcUser.getEmail();
        }
        else{
            throw new RuntimeException("Unsupported principal type: "+principal.getClass());
        }
        User user=userRepository.findByEmail(email).orElseThrow();
        Collection collection=new Collection();
        collection.setName(name);
        collection.setUser(user);
        return collectionRepository.save(collection);
    }

    public List<Collection> getCollection(){
        System.out.println("Current user"+SecurityContextHolder.getContext().getAuthentication().getName());
        Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String email;
        if(principal instanceof String){
            email=(String) principal;
        }
        else if(principal instanceof org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser oidcUser){
            email=oidcUser.getEmail();
        }
        else{
            throw new RuntimeException("Unsupported principal type: "+principal.getClass());
        }
        return collectionRepository.findByUser_Email(email);
    }
    public CollectionRequest saveRequest(CollectionRequest request){
        return collectionRequestRepository.save(request);
    }
    public List<CollectionRequest> getRequests(Long collectionId){
        return collectionRequestRepository.findByCollection_IdOrderByCreatedAtDesc(collectionId);
    }

    public CollectionRequest saveRequest(Long collectionId,CollectionRequest request){
        Collection collection=collectionRepository.findById(collectionId).orElseThrow(()->new RuntimeException("Collection not found"));
        request.setCollection(collection);

        return collectionRequestRepository.save(request);

    }
}

package com.Aryan.APIX.service;

import com.Aryan.APIX.entity.RequestHistory;
import com.Aryan.APIX.entity.User;
import com.Aryan.APIX.repository.RequestHistoryRepository;
import com.Aryan.APIX.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Sort;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryService {

    @Autowired
    private RequestHistoryRepository repository;

    @Autowired
    private UserRepository userRepository;



    public void savedHistory(String method,String url,int statusCode,long responseTime,String headers,String body){
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
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
        User user=userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found: " + email));
        RequestHistory requestHistory=new RequestHistory();
        requestHistory.setMethod(method);
        requestHistory.setUrl(url);
        requestHistory.setStatusCode(statusCode);
        requestHistory.setResponseTime(responseTime);
        requestHistory.setHeaders(headers);
        requestHistory.setBody(body);
        requestHistory.setCreatedAt(LocalDateTime.now());
        requestHistory.setUser(user);
        repository.save(requestHistory);
    }

    public List<RequestHistory> getAllHistory(){
        String email=(String)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User user=userRepository.findByEmail(email).orElseThrow(()->new RuntimeException("User not found"));
        return repository.findByUserOrderByCreatedAtDesc(user);
          }

    public List<RequestHistory> getAllHistory(String url){

        String email=(String)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        User user=userRepository.findByEmail(email).orElseThrow(()->new RuntimeException("User not found"));
        return repository.findByUserAndUrl(user,url);
    }
}

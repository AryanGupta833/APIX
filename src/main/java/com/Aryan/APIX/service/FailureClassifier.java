package com.Aryan.APIX.service;


import org.springframework.stereotype.Component;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.UnknownHostException;

@Component
public class FailureClassifier {
    public String classifyException(Exception e){
        if(e instanceof UnknownHostException){
            return "DNS";
        }
        if(e instanceof SSLHandshakeException){
            return "TLS";
        }
        if(e instanceof ConnectException){
            return "NETWORK";
        }

        return "UNKNOWN";
    }

    public String classifyStatus(int status){
        if(status==401){
            return "AUTHENTICATION";
        }
        if(status==403){
            return "AUTHORIZATION";
        }
        if(status==429){
            return "RATE_LIMIT";
        }
        if(status>=500){
            return "SERVICE";
        }
        return "NONE";
    }
}

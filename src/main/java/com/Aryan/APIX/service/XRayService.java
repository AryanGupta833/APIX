package com.Aryan.APIX.service;

import com.Aryan.APIX.model.XRayAnalysis;
import org.springframework.stereotype.Service;

@Service
public class XRayService {

    public XRayAnalysis analysis(int statusCode){
        XRayAnalysis xray=new XRayAnalysis();

        if(statusCode==401){
            xray.setLayer("Authentication Layer");
            xray.setCause("Missing or invalid authentication token");
            xray.setSuggestion("Add Authorization header or verify token");
        }
        else if(statusCode==403){
            xray.setLayer("Authorization Layer");
            xray.setCause("User does not have permission");
            xray.setSuggestion("Check roles or API access rights");
        }
        else if(statusCode==404){
            xray.setLayer("API Gateway");
            xray.setCause("Endpoint not found");
            xray.setSuggestion("Verify API URL path");
        }
        else if(statusCode==500){
            xray.setLayer("Backend Service");
            xray.setCause("Server encountered an unexpected error");
            xray.setSuggestion("Check backend logs");
        }
        else if(statusCode>=400&&statusCode<500){
            xray.setLayer("Client Request");
            xray.setCause("Invalid request parameters or headers");
            xray.setSuggestion("Validate request body and headers");
        }
        else if(statusCode>=500){
            xray.setLayer("Server Infrastructure");
            xray.setCause("Internal server or infrastructure failure");
            xray.setSuggestion("Check API server health");
        }
        else{
            xray.setLayer("Unknown");
            xray.setCause("Unable to determine failure layer");
            xray.setSuggestion("Inspect response body");
        }
        return xray;
    }

    public XRayAnalysis analyzeException(Exception e){
        XRayAnalysis xray=new XRayAnalysis();

        if(e instanceof java.net.UnknownHostException){
            xray.setLayer("DNS Layer");
            xray.setCause("Domain name could not be resolved");
            xray.setSuggestion("Check API hostname or DNS configuration");
        }

        if(e instanceof javax.net.ssl.SSLHandshakeException){
            xray.setLayer("TLS Layer");
            xray.setCause("SSL handshake failed");
            xray.setSuggestion("Verify certificate or TLS configuration");
        }
        else if(e instanceof java.net.ConnectException){
            xray.setLayer("Network Layer");
            xray.setCause("Connection refused or unreachable host");
            xray.setSuggestion("Check server availability or firewall");
        }

        else{
            xray.setLayer("Unknown Network Layer");
            xray.setCause(e.getMessage());
            xray.setSuggestion("Inspect stack trace or server logs");
        }

        return xray;
    }

}

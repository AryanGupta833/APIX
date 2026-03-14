package com.Aryan.APIX.service;

import com.Aryan.APIX.model.ErrorAnalysis;
import org.springframework.stereotype.Service;

@Service
public class ErrorAnalysisService {

    public ErrorAnalysis analysis(int statusCode){
        ErrorAnalysis analysis=new ErrorAnalysis();
        switch (statusCode){
            case 400:
                analysis.setErrorType("Bad Request");
                analysis.setPossibleCause("Invalid request syntax or parameters");
                analysis.setSuggestion("Check request body,parameters, or headers");
                break;
            case 401:
                analysis.setErrorType("Unauthorized");
                analysis.setPossibleCause("Missing or invalid authentication");
                analysis.setSuggestion("Verify Authorization header or token");
                break;
            case 403:
                analysis.setErrorType("Forbidden");
                analysis.setPossibleCause("User does not have permission");
                analysis.setSuggestion("Check user roles or access rights");
                break;
            case 404:
                analysis.setErrorType("Not Found");
                analysis.setPossibleCause("Endpoint or resource does not exist");
                analysis.setSuggestion("Verify the API URL");
                break;
            case 500:
                analysis.setErrorType("Server Error");
                analysis.setPossibleCause("Server encountered an unexpected condition");
                analysis.setSuggestion("Check server logs or backend service");
                break;
            default:
                analysis.setErrorType("Unknown");
                analysis.setPossibleCause("Unhandled HTTP status");
                analysis.setSuggestion("Inspect response body for more details");

        }
        return analysis;
    }
}

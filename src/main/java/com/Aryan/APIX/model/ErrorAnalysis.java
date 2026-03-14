package com.Aryan.APIX.model;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ErrorAnalysis {
    private String errorType;
    private String  possibleCause;
    private String suggestion;


}

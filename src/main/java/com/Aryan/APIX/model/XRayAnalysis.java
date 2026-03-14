package com.Aryan.APIX.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class XRayAnalysis {

    private String layer;
    private String cause;
    private String suggestion;
}

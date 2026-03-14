package com.Aryan.APIX.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DependencyEdge {
    private String fromService;
    private String toService;
}

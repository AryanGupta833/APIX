package com.Aryan.APIX.ai.provider;

import com.Aryan.APIX.ai.dto.AIRequest;
import com.Aryan.APIX.ai.dto.AIResponse;

public interface AIProvider {
    AIResponse analyze(AIRequest request);
}

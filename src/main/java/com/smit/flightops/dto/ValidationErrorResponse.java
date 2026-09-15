package com.smit.flightops.dto;

import java.time.Instant;
import java.util.Map;

public record ValidationErrorResponse(String code, Map<String, String> fieldErrors, Instant timestamp) {}

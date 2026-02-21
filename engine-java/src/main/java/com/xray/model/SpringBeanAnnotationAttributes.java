package com.xray.model;

import lombok.Builder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Builder
public record SpringBeanAnnotationAttributes(
        String source,
        String declaredType,
        String beanName,
        String owner,
        Boolean primary,
        List<String> qualifier,
        String scope,
        List<String> profile,
        Boolean conditional
) {

    public Map<String, Object> toNodeAttributes() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("spring.source", source);
        attributes.put("spring.declaredType", declaredType);
        attributes.put("spring.beanName", beanName);
        if (owner != null) {
            attributes.put("spring.owner", owner);
        }
        if (primary != null) {
            attributes.put("spring.primary", primary);
        }
        if (qualifier != null) {
            attributes.put("spring.qualifier", qualifier);
        }
        if (scope != null) {
            attributes.put("spring.scope", scope);
        }
        if (profile != null && !profile.isEmpty()) {
            attributes.put("spring.profile", profile);
        }
        if (conditional != null) {
            attributes.put("spring.conditional", conditional);
        }
        return attributes;
    }
}

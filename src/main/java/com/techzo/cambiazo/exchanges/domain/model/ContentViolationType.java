package com.techzo.cambiazo.exchanges.domain.model;

public enum ContentViolationType {
    SEXUAL_EXPLICIT(60, "Contenido sexual o desnudez"),
    WEAPONS_OR_DRUGS(60, "Armas, drogas u objetos ilegales"),
    VIOLENCE(60, "Imágenes violentas o sangrientas"),
    PERSONAL_INFO(60, "Datos personales visibles (DNI, tarjetas, direcciones, etc.)"),
    NONE(0, "Sin problemas detectados");
    
    private final int banDurationMinutes;
    private final String description;
    
    ContentViolationType(int banDurationMinutes, String description) {
        this.banDurationMinutes = banDurationMinutes;
        this.description = description;
    }
    
    public int getBanDurationMinutes() {
        return banDurationMinutes;
    }
    
    public String getDescription() {
        return description;
    }
    
    public static ContentViolationType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return NONE;
        }
        
        try {
            return ContentViolationType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}

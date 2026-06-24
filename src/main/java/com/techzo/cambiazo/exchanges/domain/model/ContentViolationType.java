package com.techzo.cambiazo.exchanges.domain.model;

public enum ContentViolationType {
    SEXUAL_EXPLICIT(60, "Contenido sexual o desnudez"),
    WEAPONS_OR_DRUGS(60, "Armas, drogas u objetos ilegales"),
    VIOLENCE(60, "Imágenes violentas o sangrientas"),
    // Contenido perturbador: cadáveres o cuerpos sin vida. Es grave, no un error inocente -> banea.
    CADAVER(60, "Imágenes de cadáveres o cuerpos sin vida"),
    PERSONAL_INFO(60, "Datos personales visibles (DNI, tarjetas, direcciones, etc.)"),
    // Rechazo sin baneo (0 min): subir fotos de personas o animales suele ser un error inocente,
    // no un abuso. Solo se permiten fotos de productos/objetos.
    PERSON(0, "Imágenes de personas (solo se permiten fotos de productos)"),
    ANIMAL(0, "Imágenes de animales (solo se permiten fotos de productos)"),
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

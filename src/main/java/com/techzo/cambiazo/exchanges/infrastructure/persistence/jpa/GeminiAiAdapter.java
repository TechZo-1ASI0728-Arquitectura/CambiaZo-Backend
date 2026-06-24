package com.techzo.cambiazo.exchanges.infrastructure.persistence.jpa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techzo.cambiazo.exchanges.domain.exceptions.ContentViolationException;
import com.techzo.cambiazo.exchanges.domain.model.ContentViolationType;
import com.techzo.cambiazo.exchanges.domain.ports.AiSuggestionPort;
import com.techzo.cambiazo.exchanges.interfaces.rest.resources.ProductSuggestionResource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.text.Normalizer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GeminiAiAdapter implements AiSuggestionPort {

    private final WebClient webClient;
    private final IProductCategoryRepository categoryRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.model}")
    private String modelVision;

    public GeminiAiAdapter(
            @Qualifier("geminiWebClient") WebClient geminiWebClient,
            IProductCategoryRepository categoryRepository) {
        this.webClient = geminiWebClient;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public ProductSuggestionResource suggestAllFromImage(byte[] image, String mimeType) {
        List<String> categories = categoryRepository.findAll().stream()
                .map(c -> c.getName().trim()).distinct().sorted().limit(60).toList();

        String raw = callGeminiVisionToJson(image, mimeType, categories);

        SuggestionPayload payload = parseSuggestionJson(raw);

        // Validar contenido antes de procesar
        ContentViolationType violation = ContentViolationType.fromString(
            emptyOr(payload.contentViolation(), "NONE")
        );
        
        if (violation != ContentViolationType.NONE) {
            throw new ContentViolationException(
                violation,
                emptyOr(payload.violationReason(), "Contenido no permitido por las políticas de Cambiazo")
            );
        }

        String price = normalizePrice(payload.price());
        String category = pickClosestCategory(payload.category(), categories);
        int score = normalizeScore(payload.score());

        return new ProductSuggestionResource(
                emptyOr(payload.name(), "Producto"),
                emptyOr(payload.description(), "Sin descripción"),
                emptyOr(price, "0"),
                emptyOr(category, categories.isEmpty() ? "Sin categoría" : categories.get(0)),
                emptyOr(payload.suggest(), ""),
                score
        );
    }

    private String callGeminiVisionToJson(byte[] image, String mimeType, List<String> categories) {
        String b64 = Base64.getEncoder().encodeToString(image);
        String categoriesInline = String.join("|", categories);

        String prompt = """
          Eres un asistente que analiza fotos de productos para una app de trueques. Devuelve SOLO un JSON en español, sin markdown.
          Estructura:
          {
            "n": "nombre corto del producto",
            "d": "descripción atractiva en máx 25 palabras",
            "p": número entero de precio sugerido en soles peruanos (PEN), sin texto ni símbolo,
            "c": "una categoría exacta de esta lista: [%s]",
            "s": "consejo breve para mejorar la foto (luz, fondo, enfoque, encuadre)",
            "sc": número entero del 1 al 10 evaluando calidad de la foto,
            "v": "N | SEX | ARM | VIO | CAD | PII | PER | ANI",
            "vr": "explicación en español natural y amable (1-2 frases) de por qué se marcó la violación, dirigida al usuario"
          }
          Para "v":
            N   = sin problemas
            SEX = contenido sexual o desnudez
            ARM = armas, drogas u objetos ilegales
            VIO = violencia o sangre
            CAD = cadáveres o cuerpos sin vida (humanos o animales muertos)
            PII = datos personales visibles (DNI, tarjetas, direcciones, placas, etc.)
            PER = la imagen muestra a una persona viva como sujeto principal: rostro o cuerpo de bebés, niños o adultos. Cambiazo solo permite fotos de productos u objetos, no de personas. Un producto sostenido por una mano no cuenta como persona.
            ANI = la imagen muestra a un animal o mascota vivo como sujeto principal (perro, gato, ave, etc.). Solo se permiten fotos de productos u objetos, no de animales. Un producto con estampado o forma de animal (peluche, juguete) NO cuenta como animal.
          Si aplican varias categorías, prioriza en este orden de gravedad: SEX, ARM, VIO, CAD, PII, PER, ANI.
          Si v = N, "vr" debe ser una cadena vacía.
          Si v ≠ N, "vr" debe explicar al usuario qué se detectó y por qué no puede publicarlo, en español claro y sin tecnicismos.
        """.formatted(categoriesInline);

        var body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt),
                                Map.of("inlineData", Map.of(
                                        "mimeType", mimeType,
                                        "data", b64
                                ))
                        ))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.2,
                        "responseMimeType", "application/json"
                )
        );

        try {
            return webClient.post()
                    .uri("/" + modelVision + ":generateContent?key=" + geminiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    // Reintenta ante saturación/errores transitorios de Gemini con backoff exponencial.
                    // Hasta 3 reintentos: ~0.7s, ~1.4s, ~2.8s (con jitter, tope 4s).
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(700))
                            .maxBackoff(Duration.ofSeconds(4))
                            .jitter(0.3)
                            .filter(GeminiAiAdapter::isRetryableGeminiError)
                            // Tras agotar reintentos, relanza el error original (no el de Reactor)
                            // para que el catch de abajo lo maneje igual que siempre.
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .block();
        } catch (WebClientResponseException e) {
            throw new IllegalStateException("Gemini vision error " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * Determina si un error de Gemini es transitorio y vale la pena reintentar:
     * 503 (saturación / "high demand"), 429 (rate limit) o 500 (error interno).
     * El resto (400, 401, 404, etc.) son errores definitivos y no se reintentan.
     */
    private static boolean isRetryableGeminiError(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            int status = e.getStatusCode().value();
            return status == 503 || status == 429 || status == 500;
        }
        return false;
    }

    private record SuggestionPayload(String name, String description, String price, String category,
                                     String suggest, String score, String contentViolation, String violationReason) {}

    private SuggestionPayload parseSuggestionJson(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                for (JsonNode p : parts) {
                    if (p.has("text")) {
                        String cleaned = trimFence(p.get("text").asText().trim());
                        JsonNode obj = mapper.readTree(cleaned);
                        return new SuggestionPayload(
                                firstNonBlank(obj, "n", "name"),
                                firstNonBlank(obj, "d", "description"),
                                firstNonBlank(obj, "p", "price"),
                                firstNonBlank(obj, "c", "category"),
                                firstNonBlank(obj, "s", "suggest"),
                                firstNonBlank(obj, "sc", "score"),
                                expandViolation(firstNonBlank(obj, "v", "contentViolation")),
                                firstNonBlank(obj, "vr", "violationReason")
                        );
                    }
                }
            }
        } catch (Exception ignored) {}
        return new SuggestionPayload("", "", "", "", "", "", "NONE", "");
    }

    private static String firstNonBlank(JsonNode obj, String... fields) {
        for (String f : fields) {
            String v = optText(obj, f);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String expandViolation(String code) {
        if (code == null || code.isBlank()) return "NONE";
        return switch (code.trim().toUpperCase(Locale.ROOT)) {
            case "N", "NONE" -> "NONE";
            case "SEX", "SEXUAL_EXPLICIT" -> "SEXUAL_EXPLICIT";
            case "ARM", "WEAPONS_OR_DRUGS" -> "WEAPONS_OR_DRUGS";
            case "VIO", "VIOLENCE" -> "VIOLENCE";
            case "CAD", "CADAVER" -> "CADAVER";
            case "PII", "PERSONAL_INFO" -> "PERSONAL_INFO";
            case "PER", "PERSON" -> "PERSON";
            case "ANI", "ANIMAL" -> "ANIMAL";
            default -> "NONE";
        };
    }

    private static String trimFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            int idx = t.indexOf('\n');
            if (idx > 0) t = t.substring(idx + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String optText(JsonNode obj, String field) {
        return obj.has(field) && !obj.get(field).isNull() ? obj.get(field).asText() : "";
    }

    private static String normalizePrice(String p) {
        if (p == null) return "0";
        String digits = p.replaceAll("[^0-9.,]", "").replace(",", ".");
        if (digits.endsWith(".")) digits = digits.substring(0, digits.length() - 1);
        return digits.isBlank() ? "0" : digits;
    }

    private static int normalizeScore(String s) {
        if (s == null || s.isBlank()) return 5;
        try {
            int v;
            if (s.contains(".") || s.contains(",")) {
                double d = Double.parseDouble(s.replace(",", "."));
                v = (int) Math.round(d);
            } else {
                v = Integer.parseInt(s.trim());
            }
            if (v < 1) v = 1;
            if (v > 10) v = 10;
            return v;
        } catch (Exception e) {
            return 5;
        }
    }

    private static String emptyOr(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }

    private static String normalize(String s) {
        String n = s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
        n = Normalizer.normalize(n, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("\\s+", " ");
    }

    private static String pickClosestCategory(String predicted, List<String> categories) {
        if (categories == null || categories.isEmpty()) return predicted == null ? "" : predicted;

        String np = normalize(predicted);
        Map<String, String> normalizedMap = categories.stream()
                .collect(Collectors.toMap(GeminiAiAdapter::normalize, c -> c, (a, b) -> a));

        if (normalizedMap.containsKey(np)) return normalizedMap.get(np);

        for (var e : normalizedMap.entrySet()) {
            if (np.contains(e.getKey()) || e.getKey().contains(np)) {
                return e.getValue();
            }
        }

        String otros = categories.stream()
                .filter(c -> normalize(c).contains("otros") || normalize(c).contains("varios"))
                .findFirst().orElse(null);

        return otros != null ? otros : categories.get(0);
    }
}
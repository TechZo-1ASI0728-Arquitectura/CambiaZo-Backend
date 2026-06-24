package com.techzo.cambiazo.exchanges.interfaces.rest;

import com.techzo.cambiazo.exchanges.application.internal.services.ExchangeAiService;
import com.techzo.cambiazo.exchanges.application.internal.outboundservices.IamAclOutboundService;
import com.techzo.cambiazo.exchanges.application.internal.commandservices.ContentViolationCommandServiceImpl;
import com.techzo.cambiazo.exchanges.domain.exceptions.ContentViolationException;
import com.techzo.cambiazo.exchanges.domain.model.commands.ProcessContentViolationCommand;
import com.techzo.cambiazo.exchanges.interfaces.rest.resources.ContentViolationResource;
import com.techzo.cambiazo.exchanges.interfaces.rest.resources.ProductSuggestionResource;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/exchanges/ai")
@Tag(name = "AI", description = "AI endpoints for product suggestions (by image)")
public class ExchangeAiController {

    private final ExchangeAiService aiService;
    private final IamAclOutboundService iamAclOutboundService;
    private final ContentViolationCommandServiceImpl contentViolationCommandService;

    public ExchangeAiController(ExchangeAiService aiService, IamAclOutboundService iamAclOutboundService, ContentViolationCommandServiceImpl contentViolationCommandService) {
        this.aiService = aiService;
        this.iamAclOutboundService = iamAclOutboundService;
        this.contentViolationCommandService = contentViolationCommandService;
    }

    @PostMapping(value="/suggest", consumes=MediaType.MULTIPART_FORM_DATA_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> suggestFromImage(@RequestParam("file") MultipartFile file, @RequestParam("userId") Long userId) throws Exception {
        // Validate user exists
        if (!iamAclOutboundService.validateUserExists(userId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Usuario no encontrado", "message", "El userId proporcionado no existe"));
        }

        // Validate user is not banned
        if (iamAclOutboundService.isUserBanned(userId)) {
            long remainingMinutes = iamAclOutboundService.getRemainingBanMinutes(userId);
            Map<String, Object> banInfo = iamAclOutboundService.getUserBanInfo(userId);

            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error", "Usuario baneado",
                            "message", "No puedes usar esta funcionalidad porque tu cuenta está temporalmente suspendida",
                            "remainingBanMinutes", remainingMinutes + 1,
                            "reason", "Tu cuenta ha sido suspendida por violar las políticas de contenido",
                            "isBanned", banInfo.get("isBanned"),
                            "bannedUntil", banInfo.get("bannedUntil")
                    ));

        }

        try {
            String ct = file.getContentType() != null ? file.getContentType() : MediaType.IMAGE_JPEG_VALUE;
            ProductSuggestionResource suggestion = aiService.suggestAllFromImage(file.getBytes(), ct);
            return ResponseEntity.ok(suggestion);
        } catch (ContentViolationException e) {
            var violationType = e.getViolationType();

            // Violaciones leves (ban = 0 min, p. ej. fotos de personas) solo se rechazan,
            // sin castigar al usuario. Las graves (sexual, armas, violencia, datos) sí banean.
            if (violationType.getBanDurationMinutes() <= 0) {
                ContentViolationResource rejection = new ContentViolationResource(
                        violationType.getDescription(),
                        e.getReason(),
                        0,
                        "Política de contenido de Cambiazo",
                        false,
                        null
                );
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(rejection);
            }

            // Process the content violation and ban the user
            var violationCommand = new ProcessContentViolationCommand(
                userId,
                violationType,
                e.getReason()
            );

            // Process the violation and ban the user
            contentViolationCommandService.processContentViolation(violationCommand);

            // Get remaining ban minutes for response
            long remainingMinutes = contentViolationCommandService.getRemainingBanMinutes(userId);
            Map<String, Object> banInfo = iamAclOutboundService.getUserBanInfo(userId);


            ContentViolationResource violation = new ContentViolationResource(
                    violationType.getDescription(),
                    e.getReason(),
                    (int) remainingMinutes + 1,
                    "Política de contenido de Cambiazo",
                    (Boolean) banInfo.get("isBanned"),
                    (LocalDateTime) banInfo.get("bannedUntil")
            );


            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(violation);
        }
    }
}

package com.techzo.cambiazo.exchanges.application.internal.commandservices;

import jakarta.annotation.PostConstruct;
import com.techzo.cambiazo.shared.infrastructure.storage.AzureBlobStorageService;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.techzo.cambiazo.exchanges.domain.model.commands.CreateInvoiceCommand;
import com.techzo.cambiazo.exchanges.domain.model.entities.Invoice;
import com.techzo.cambiazo.exchanges.domain.services.IInvoiceCommandService;
import com.techzo.cambiazo.exchanges.infrastructure.persistence.jpa.IInvoiceRepository;
import com.techzo.cambiazo.iam.domain.model.aggregates.User;
import com.techzo.cambiazo.iam.infrastructure.persistence.jpa.repositories.UserRepository;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.time.ZonedDateTime;
import static java.awt.Color.BLACK;

@Service
public class InvoiceCommandServiceImpl implements IInvoiceCommandService {

    private final IInvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final JavaMailSender mailSender;
    private final AzureBlobStorageService storageService;

    public InvoiceCommandServiceImpl(IInvoiceRepository invoiceRepository,
                                     UserRepository userRepository,
                                     JavaMailSender mailSender,
                                     AzureBlobStorageService storageService) {
        this.invoiceRepository = invoiceRepository;
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.storageService = storageService;
    }

    @Override
    public Optional<Invoice> handle(CreateInvoiceCommand command) {
        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        String invoiceNumber = "INV-"
                + LocalDate.now().format(DateTimeFormatter.ISO_DATE)
                + "-" + System.currentTimeMillis();

        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "cambiazo", "invoices");
        try {
            Files.createDirectories(tempDir);
        } catch (Exception e) {
            throw new RuntimeException("No se pudo crear el directorio de boletas", e);
        }

        String pdfFilename = invoiceNumber + ".pdf";
        String filePath = tempDir.resolve(pdfFilename).toString();

        Invoice invoice = new Invoice(invoiceNumber, command.totalAmount(),
                command.concept(), filePath, user);
        try {
            byte[] pdf = buildPdfBytes(invoice);
            String objectName = "invoices/" + invoiceNumber + ".pdf";
            String url = storageService.uploadBytes(pdf, objectName, "application/pdf");
            invoice.setFilePath(url);

            invoiceRepository.save(invoice);
            sendInvoiceEmail(user.getUsername(), invoice, pdf);
        } catch (Exception e) {
            throw new RuntimeException("Error al generar o subir la boleta", e);
        }

        return Optional.of(invoice);
    }

    private static final Color LIGHT = new Color(250, 250, 250);
    private static final Font  TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 26, BLACK);
    private static final Font  LABEL = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.DARK_GRAY);
    private static final Font  VALUE = FontFactory.getFont(FontFactory.HELVETICA,      12, BLACK);
    private static final Font  FOOT  = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 11, Color.GRAY);

    private byte[] buildPdfBytes(Invoice invoice) throws IOException, DocumentException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 90, 50);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Image logo = Image.getInstance("https://cambiazo-site.netlify.app/assets/logo/cambiazo_logo.png");
        logo.scaleToFit(140, 70);

        Paragraph companyInfo = new Paragraph("""
    CambiaZo
    Av.Los Helechos 123, Lima
    """, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13));

        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{2, 5});

        PdfPCell logoCell = new PdfPCell(logo, false);
        logoCell.setBorder(PdfPCell.NO_BORDER);
        logoCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        logoCell.setVerticalAlignment(Element.ALIGN_TOP);

        PdfPCell infoCell = new PdfPCell();
        infoCell.addElement(companyInfo);
        infoCell.setBorder(PdfPCell.NO_BORDER);
        infoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        header.addCell(infoCell);
        header.addCell(logoCell);
        doc.add(header);

        Paragraph title = new Paragraph("COMPROBANTE DE PAGO", TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingBefore(25);
        title.setSpacingAfter(25);
        doc.add(title);

        PdfPTable wrapper = new PdfPTable(1);
        wrapper.setWidthPercentage(100);
        PdfPCell shadow = new PdfPCell();
        shadow.setBorder(PdfPCell.NO_BORDER);
        shadow.setCellEvent((cell, position, canvases) -> {
            Rectangle r = new Rectangle(position.getLeft()-2, position.getBottom()-2,
                    position.getRight()+2, position.getTop()+2);
            PdfContentByte cb = canvases[PdfPTable.BACKGROUNDCANVAS];
            cb.setColorFill(new Color(230, 230, 230));
            cb.roundRectangle(r.getLeft(), r.getBottom(), r.getWidth(), r.getHeight(), 6);
            cb.fill();
        });
        shadow.addElement(buildDetailTable(invoice));
        wrapper.addCell(shadow);
        doc.add(wrapper);

        Paragraph foot = new Paragraph(
                "Gracias por confiar en CambiaZo. Conserve el comprobante de su pago.",
                FOOT);
        foot.setAlignment(Element.ALIGN_CENTER);
        foot.setSpacingBefore(30);
        doc.add(foot);

        doc.close();
        return out.toByteArray();
    }

    private PdfPTable buildDetailTable(Invoice inv) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidths(new float[]{1.6f, 3.4f});
        t.setWidthPercentage(100);
        ZonedDateTime issuedAt = inv.getIssuedAt().atZone(ZoneId.of("America/Lima"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd 'de' MMMM yyyy – HH:mm")
                .withLocale(new Locale("es", "PE"))
                .withZone(ZoneId.of("America/Lima"));

        addRow(t,"Cliente:",  inv.getUser().getName());
        addRow(t,"Número:",   inv.getInvoiceNumber());
        addRow(t,"Emitido:",  fmt.format(issuedAt));
        addRow(t,"Monto:",    "$ " + String.format(Locale.US,"%,.2f", inv.getAmount()));
        addRow(t,"Concepto:", inv.getDescription());
        return t;
    }

    private void addRow(PdfPTable t, String label, String value) {
        boolean zebra = (t.getRows().size() % 2) == 0;
        Color bg = zebra ? LIGHT : Color.WHITE;
        PdfPCell l = new PdfPCell(new Phrase(label, LABEL));
        PdfPCell v = new PdfPCell(new Phrase(value, VALUE));
        Stream.of(l,v).forEach(c -> {
            c.setBorder(PdfPCell.NO_BORDER);
            c.setBackgroundColor(bg);
            c.setPadding(8);
        });
        t.addCell(l);
        t.addCell(v);
    }

    private void sendInvoiceEmail(String to, Invoice invoice, byte[] pdf) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setTo(to);
            helper.setSubject("Gracias por tu suscripción a CambiaZo");

            String html = """
            <html>
            <body>
              <p>Hola %s,</p>
              <p>¡Muchas gracias por tu suscripción a <strong>CambiaZo</strong>!</p>
              <p>Nos complace darte la bienvenida a nuestra comunidad de usuarios. A partir de hoy podrás disfrutar de:</p>
              <ul>
                <li>Acceso prioritario a nuevas funcionalidades.</li>
                <li>Soporte dedicado 24/7 para resolver cualquier duda.</li>
              </ul>
              <p>Visita ahora para probar tus beneficios <a href="https://cambia-zo.netlify.app/">CambiaZo</a>.</p>
              <p>Detalles de tu suscripción:</p>
              <table cellpadding="6" cellspacing="0" border="1">
                <tr><td><strong>Número de comprobante </strong></td><td>%s</td></tr>
                <tr><td><strong>Fecha de emisión</strong></td><td>%s</td></tr>
                <tr><td><strong>Monto</strong></td><td>$ %,.2f</td></tr>
                <tr><td><strong>Concepto</strong></td><td>%s</td></tr>
              </table>
              <p>Si tienes alguna pregunta o necesitas asistencia adicional, no dudes en contactarnos respondiendo a este correo o a través de nuestro chat en línea.</p>
              <p>¡Gracias por confiar en CambiaZo!</p>
              <p>Saludos cordiales,<br/>
                 El equipo de CambiaZo</p>
            </body>
            </html>
            """.formatted(
                    invoice.getUser().getName(),
                    invoice.getInvoiceNumber(),
                    invoice.getIssuedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                    invoice.getAmount(),
                    invoice.getDescription()
            );

            helper.setText(html, true);
            helper.addAttachment(invoice.getInvoiceNumber() + ".pdf",
                    new ByteArrayResource(pdf));

            File file = new File(invoice.getFilePath());
            if (file.exists()) {
                helper.addAttachment(file.getName(), file);
            }

            mailSender.send(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

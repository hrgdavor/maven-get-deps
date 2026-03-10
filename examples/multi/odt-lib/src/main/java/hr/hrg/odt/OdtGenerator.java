package hr.hrg.odt;

import org.odftoolkit.odfdom.doc.OdfTextDocument;
import org.odftoolkit.odfdom.dom.element.office.OfficeTextElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Simple library to generate ODT files.
 */
public class OdtGenerator {
    private static final Logger logger = LoggerFactory.getLogger(OdtGenerator.class);

    public void createOdt(String text, File outputFile) throws Exception {
        OdfTextDocument odt = OdfTextDocument.newTextDocument();
        OfficeTextElement officeText = odt.getContentRoot();

        // Split by lines and add as paragraphs
        String[] lines = text.split("\n");
        for (String line : lines) {
            odt.newParagraph(line);
        }

        odt.save(outputFile);
    }
}

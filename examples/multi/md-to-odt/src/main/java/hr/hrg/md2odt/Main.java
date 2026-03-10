package hr.hrg.md2odt;

import hr.hrg.odt.OdtGenerator;
import org.apache.commons.io.FileUtils;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * Daemon that watches a folder for Markdown files and converts them to ODT.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            logger.error("Usage: java hr.hrg.md2odt.Main <input-dir> <output-dir>");
            System.exit(1);
        }

        Path inputDir = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);

        if (!Files.exists(inputDir))
            Files.createDirectories(inputDir);
        if (!Files.exists(outputDir))
            Files.createDirectories(outputDir);

        logger.info("Starting Markdown to ODT Watcher...");
        logger.info("Watching: {}", inputDir.toAbsolutePath());
        logger.info("Output:   {}", outputDir.toAbsolutePath());

        WatchService watchService = FileSystems.getDefault().newWatchService();
        inputDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        Parser parser = Parser.builder().build();
        TextContentRenderer renderer = TextContentRenderer.builder().build();
        OdtGenerator generator = new OdtGenerator();

        while (true) {
            WatchKey key = watchService.poll(10, TimeUnit.SECONDS);
            if (key == null)
                continue;

            for (WatchEvent<?> event : key.pollEvents()) {
                Path filename = (Path) event.context();
                if (filename.toString().endsWith(".md")) {
                    File inputFile = inputDir.resolve(filename).toFile();
                    String mdContent = FileUtils.readFileToString(inputFile, "UTF-8");

                    Node document = parser.parse(mdContent);
                    String plainText = renderer.render(document);

                    String odtFilename = filename.toString().replace(".md", ".odt");
                    File outputFile = outputDir.resolve(odtFilename).toFile();

                    logger.info("Converting: {} -> {}", filename, odtFilename);
                    generator.createOdt(plainText, outputFile);
                }
            }
            key.reset();
        }
    }
}

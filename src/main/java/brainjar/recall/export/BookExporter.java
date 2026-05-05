package brainjar.recall.export;

import brainjar.recall.model.Book;
import brainjar.recall.model.Page;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class BookExporter {

    public int export(List<Page> pages, Path outputDir, String shelfFilter) throws IOException {
        var filtered = shelfFilter == null ? pages
                : pages.stream().filter(p -> p.book().shelf().name().equals(shelfFilter)).toList();
        var grouped = groupByBook(filtered);
        for (var entry : grouped.entrySet()) {
            writeBook(entry.getKey(), entry.getValue(), outputDir);
        }
        return grouped.size();
    }

    private void writeBook(Book book, List<Page> pages, Path outputDir) throws IOException {
        var dir = outputDir.resolve(book.shelf().name());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(buildFilename(book)), buildContent(pages));
    }

    static String buildFilename(Book book) {
        var date = book.lastModified() != null
                ? book.lastModified().atZone(ZoneOffset.UTC).toLocalDate().toString()
                : "0000-00-00";
        var title = book.title().replace(' ', '-');
        var stem = title.endsWith(".md") ? title.substring(0, title.length() - 3) : title;
        return date + "-" + stem + ".md";
    }

    static String buildContent(List<Page> pages) {
        return pages.stream()
                .sorted(Comparator.comparingInt(Page::chunkIndex))
                .map(Page::content)
                .collect(Collectors.joining("\n\n"));
    }

    static Map<Book, List<Page>> groupByBook(List<Page> pages) {
        var byPath = pages.stream().collect(
                Collectors.groupingBy(p -> p.book().sourcePath(), LinkedHashMap::new, Collectors.toList()));
        return byPath.entrySet().stream().collect(Collectors.toMap(
                e -> mostRecentBook(e.getValue()),
                Map.Entry::getValue,
                (a, b) -> a,
                LinkedHashMap::new));
    }

    private static Book mostRecentBook(List<Page> pages) {
        return pages.stream()
                .map(Page::book)
                .max(Comparator.comparing(b -> b.lastModified() != null ? b.lastModified() : Instant.MIN))
                .orElseThrow();
    }
}

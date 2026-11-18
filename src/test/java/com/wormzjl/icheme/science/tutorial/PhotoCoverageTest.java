package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.PhotoFile;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guide's photographs (batch 2026-11-18-tutorial-photos): every chapter has one; every photo a chapter names has its
 * PNG on the class path at the manifest's size, at most 384 px and 150 KB, its caption line, a name line where its chapter
 * has several, and its row in {@code PHOTO_CREDITS.md}; no PNG in the folder is unused.
 */
class PhotoCoverageTest {
    static final int MAX_SIDE = 384, MAX_BYTES = 150 * 1024;

    @Test
    void everyChapterHasAPhotoWithItsTextureCaptionAndCredit() throws IOException {
        var guide = GuideFile.load();
        var photos = PhotoFile.load();
        var lang = TutorialText.english();
        String credits;
        try (InputStream in = PhotoCoverageTest.class.getResourceAsStream("/assets/icheme/tutorial/PHOTO_CREDITS.md")) {
            assertNotNull(in, "PHOTO_CREDITS.md ships");
            credits = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var faults = new ArrayList<String>();
        var used = new HashSet<String>();
        for (var chapter : guide.chapters()) {
            if (chapter.photos().isEmpty()) faults.add(chapter.id() + ": no photo");
            for (var id : chapter.photos()) {
                used.add(id);
                var photo = photos.photo(id);
                if (photo == null) {
                    faults.add(id + ": not in photos.json");
                    continue;
                }
                try (InputStream in = PhotoCoverageTest.class.getResourceAsStream("/assets/icheme/textures/tutorial/photo/" + id + ".png")) {
                    if (in == null) {
                        faults.add(id + ": no PNG");
                        continue;
                    }
                    byte[] bytes = in.readAllBytes();
                    var image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
                    if (image.getWidth() != photo.w() || image.getHeight() != photo.h()) faults.add(id + ": size differs from photos.json");
                    if (Math.max(image.getWidth(), image.getHeight()) > MAX_SIDE) faults.add(id + ": over " + MAX_SIDE + " px");
                    if (bytes.length > MAX_BYTES) faults.add(id + ": " + bytes.length + " bytes");
                }
                if (lang.get(photo.captionKey()) == null) faults.add(id + ": no caption line");
                if (chapter.photos().size() > 1 && lang.get(photo.nameKey()) == null) faults.add(id + ": no name line");
                if (!credits.contains("\n| " + id + " |")) faults.add(id + ": no credits row");
            }
        }
        for (var id : photos.photos().keySet()) if (!used.contains(id)) faults.add(id + ": in photos.json, in no chapter");
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/assets/icheme/textures/tutorial/photo"))) {
            for (var file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".png") || !used.contains(name.substring(0, name.length() - 4))) faults.add(name + ": in the folder, in no chapter");
            }
        }
        assertEquals(List.of(), faults);
    }
}

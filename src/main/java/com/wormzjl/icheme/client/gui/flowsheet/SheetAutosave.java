package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/**
 * The editor's autosave, off the render thread (batch 2026-11-02-process-simulator, PERFORMANCE_REVIEW.md proposal 4): one writer
 * for the client, and each file's newest sheet wins. A disk that stalls (4.6 s in the bench) holds only the writer; a sheet asked
 * for while another is written replaces the one that still waits for the same file, and the files wait in the order first asked
 * for, so a second sheet opened meanwhile never takes the first one's last edits. A sheet is immutable, so the writer reads it as
 * it was asked for. A write that fails hands its sheet back once, on the writer's thread, so the screen can say so on its status line.
 */
public final class SheetAutosave {
    /** How a sheet reaches its file: {@link FlowsheetFile#save}, whole or not at all. */
    interface Writer {
        void write(Path file, Flowsheet sheet) throws IOException;
    }

    public static final SheetAutosave CLIENT = new SheetAutosave(FlowsheetFile::save);
    static final String THREAD = "IChemE simulator autosave";

    private record Job(Path file, Flowsheet sheet, Consumer<Flowsheet> failed) {
    }

    private final Writer writer;
    /** One job a file, the newest sheet asked for it, in the order the files were first asked for. */
    private final LinkedHashMap<Path, Job> waiting = new LinkedHashMap<>();
    private boolean writing;

    SheetAutosave(Writer writer) {
        this.writer = writer;
    }

    /**
     * Writes {@code sheet} to {@code file} on the writer's thread, after the write under way and the files that wait before it.
     * {@code failed} hears the sheet if its write fails. The thread ends once nothing waits, and is no daemon, so a quitting game
     * still finishes the write under way.
     */
    public synchronized void save(Path file, Flowsheet sheet, Consumer<Flowsheet> failed) {
        waiting.put(file, new Job(file, sheet, failed));
        if (writing) return;
        writing = true;
        Thread.ofPlatform().name(THREAD).start(this::drain);
    }

    private void drain() {
        while (true) {
            Job job;
            synchronized (this) {
                var next = waiting.values().iterator();
                if (!next.hasNext()) {
                    writing = false;
                    return;
                }
                job = next.next();
                next.remove();
            }
            try {
                writer.write(job.file(), job.sheet());
            } catch (IOException | RuntimeException unwritten) {
                job.failed().accept(job.sheet());
            }
        }
    }
}

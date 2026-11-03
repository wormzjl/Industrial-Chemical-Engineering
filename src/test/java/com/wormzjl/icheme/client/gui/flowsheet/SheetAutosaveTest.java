package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The autosave writes off the render thread (P3, 2026-11-04): one writer, the newest sheet wins, and a failed write hands its sheet
 * back so the screen says so on its status line.
 */
class SheetAutosaveTest {
    private static final Path FILE = Path.of("autosave.json");

    private static Flowsheet sheet() {
        return new Flowsheet(SheetFixture.basis(), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the writer did not get there");
    }

    @Test
    void aStalledWriteHoldsOnlyTheWriterAndTheNewestSheetWins() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var written = new CopyOnWriteArrayList<Flowsheet>();
        var threads = new CopyOnWriteArrayList<String>();
        var autosave = new SheetAutosave((file, sheet) -> {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException stopped) {
                throw new IOException(stopped);
            }
            written.add(sheet);
            threads.add(Thread.currentThread().getName());
            done.countDown();
        });
        Flowsheet first = sheet(), second = sheet(), third = sheet();
        autosave.save(FILE, first, failed -> fail("no write fails"));
        await(entered);
        // The disk stalls on the first: the next two return at once, and only the newest waits.
        autosave.save(FILE, second, failed -> fail("no write fails"));
        autosave.save(FILE, third, failed -> fail("no write fails"));
        release.countDown();
        await(done);
        assertEquals(2, written.size());
        assertSame(first, written.get(0));
        assertSame(third, written.get(1), "the newest sheet, not the one it replaced");
        assertEquals(List.of(SheetAutosave.THREAD, SheetAutosave.THREAD), threads, "written on the writer's thread");
    }

    @Test
    void aFailedWriteHandsItsSheetBackAndTheNextOneWrites() throws InterruptedException {
        var refused = sheet();
        var kept = sheet();
        var written = new AtomicReference<Flowsheet>();
        var wrote = new CountDownLatch(1);
        var autosave = new SheetAutosave((file, sheet) -> {
            if (sheet == refused) throw new IOException("disk full");
            written.set(sheet);
            wrote.countDown();
        });
        var back = new AtomicReference<Flowsheet>();
        var failed = new CountDownLatch(1);
        autosave.save(FILE, refused, sheet -> {
            back.set(sheet);
            failed.countDown();
        });
        await(failed);
        assertSame(refused, back.get());
        autosave.save(FILE, kept, sheet -> fail("this write does not fail"));
        await(wrote);
        assertSame(kept, written.get());
    }
}

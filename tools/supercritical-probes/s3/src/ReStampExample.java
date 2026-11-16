import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.RevisionNotice;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read, stamp, write (the method of commit 1bd0b4236): each bundled simulator example on today's data. Args: <in dir> <out dir> <names...> */
public class ReStampExample {
    public static void main(String[] args) throws Exception {
        var catalog = MaterialCatalog.bundled();
        for (int i = 2; i < args.length; i++) {
            var sheet = FlowsheetFile.read(Files.readAllBytes(Path.of(args[0], args[i])));
            String before = sheet.basis().revision();
            var stamped = RevisionNotice.stamped(sheet, catalog);
            Files.write(Path.of(args[1], args[i]), FlowsheetFile.write(stamped));
            System.out.println(args[i] + " moved=" + !before.equals(stamped.basis().revision()));
            System.out.println("  before " + before);
            System.out.println("  after  " + stamped.basis().revision());
        }
    }
}

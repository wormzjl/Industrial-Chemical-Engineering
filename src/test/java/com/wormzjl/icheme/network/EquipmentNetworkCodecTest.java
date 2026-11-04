package com.wormzjl.icheme.network;

import com.wormzjl.icheme.client.gui.flowsheet.SizingCoverageTest;
import com.wormzjl.icheme.network.EquipmentNetwork.Action;
import com.wormzjl.icheme.network.EquipmentNetwork.ConstructorPayload;
import com.wormzjl.icheme.network.EquipmentNetwork.ImportAnswer;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.SentDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.Design;
import com.wormzjl.icheme.world.level.block.entity.SentDesignTags;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The equipment protocol's wire (batch 2026-10-27-constructor-import): the screen's draft crosses with what to do with
 * it, an import lands on that draft, and the answer carries the imported design whole or only why there is none. S5's Import
 * gate (batch 2026-11-02-process-simulator): the list and the choice cross, a simulator's designs read back as it stored them, and
 * every size the stage proposes lands on the draft as the equipment it is.
 */
class EquipmentNetworkCodecTest {
    private static final BlockPos AT = new BlockPos(40, 70, -12);
    private static final ColumnComponents COMPONENTS = new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.IN_LINE,
            List.of(ColumnComponents.Pump.STANDARD), true, false);
    /** The constructor's stored design: a 6 m column imported earlier, right in front of the constructor. */
    private static final Design STORED = Design.DEFAULT.imported(new ColumnDesign(new ColumnTrays(20, 15, List.of(8), List.of(21),
            List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS), 6));
    /** The open screen's draft: the player moved the column, repainted it and turned its sump steam to the front. */
    private static final Design DRAFT = new Design(STORED.diameter(), STORED.tangentLength(), STORED.form(), STORED.support(), new VesselPlacement(5, -2, 1),
            STORED.trays(), STORED.connections(), new Paint(0x123456, 0x654321), STORED.bank(), STORED.boot(), STORED.outlet(), STORED.column(),
            List.of(new ColumnTrays.Turn(EquipmentShape.Role.STEAM, 1, 0)), com.wormzjl.icheme.science.equipment.TowerBank.ONE);

    private static <T> T roundTrip(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T payload) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, payload);
            var read = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return read;
        } finally {
            buffer.release();
        }
    }

    @Test
    void aColumnTemplateCrossesWithItsNameAndInput() {
        var c = com.wormzjl.icheme.science.material.MaterialCatalog.bundled();
        var input = c.presets().column(c.presets().network().defaultColumnPreset()).input(c);
        var read = roundTrip(EquipmentNetwork.TemplatePayload.STREAM_CODEC, new EquipmentNetwork.TemplatePayload(AT, "Medium crude (default)", input));
        assertEquals(AT, read.position());
        assertEquals("Medium crude (default)", read.name());
        assertEquals(input, read.input());
    }

    @Test
    void aDraftCrossesWithWhatToDoWithIt() {
        for (var action : Action.values()) {
            var read = roundTrip(ConstructorPayload.STREAM_CODEC, ConstructorPayload.of(AT, DRAFT, action));
            assertEquals(AT, read.position());
            assertEquals(action, read.action());
            // The payload carries no column: the server keeps its own.
            assertEquals(DRAFT, read.design(STORED));
        }
    }

    /** Import sends the draft first: a simulator's column lands on it as the calculator's design did, and the player's edits stay. */
    @Test
    void anImportLandsOnTheDraftTheScreenSent() {
        var next = new ColumnDesign(new ColumnTrays(24, 12, List.of(6), List.of(25), List.of(new ColumnTrays.Pumparound(6, 4)), COMPONENTS), 4);
        var imported = roundTrip(ConstructorPayload.STREAM_CODEC, ConstructorPayload.of(AT, DRAFT, Action.KEEP)).design(STORED).landed(SentDesign.column("T-101", next));
        assertEquals(next, imported.column());
        assertEquals(DRAFT.placement(), imported.placement());
        assertEquals(DRAFT.paint(), imported.paint());
        assertEquals(DRAFT.turns(), imported.turns());
        // On the stored design, as before the fix, the edits are lost.
        assertNotEquals(DRAFT.placement(), STORED.imported(next).placement());
    }

    /** The list request and the choice cross whole: the choice names its design by place, tag and member, and carries the name the page listed (N1). */
    @Test
    void theListAndTheChoiceCross() {
        assertEquals(AT, roundTrip(EquipmentNetwork.ImportList.STREAM_CODEC, new EquipmentNetwork.ImportList(AT)).position());
        var choice = new EquipmentNetwork.ImportChoice(AT, 3, "T-101", "pa 2 cooler", "T-101 PA 2 cooler");
        assertEquals(choice, roundTrip(EquipmentNetwork.ImportChoice.STREAM_CODEC, choice));
    }

    /** What Send to block sends reads back on the server as the client wrote it; the list the constructor gets reads back the same way. */
    @Test
    void aSimulatorsDesignsReadBackAsSent() {
        var sent = sent();
        var read = roundTrip(ProcessSimulatorNetwork.SendDesigns.STREAM_CODEC, ProcessSimulatorNetwork.SendDesigns.of(7, sent));
        assertEquals(7, read.container());
        var whole = SentDesignTags.read(read.designs().getList("Designs", net.minecraft.nbt.Tag.TAG_COMPOUND));
        assertTrue(whole.whole(), whole.repairs().toString());
        assertEquals(sent, whole.designs());
        assertEquals(sent, new EquipmentNetwork.ImportListAnswer(AT, net.minecraft.network.chat.Component.empty(), read.designs()).listed());
    }

    /** Every size the stage proposes, as Send to block stores it. */
    private static List<SentDesign> sent() {
        var out = new ArrayList<SentDesign>();
        for (var size : SizingCoverageTest.samples()) {
            var shape = size.shape();
            if (shape == null) continue;
            out.add(size instanceof ColumnSizing.Size c ? SentDesign.column("T-101", new ColumnDesign(c.layout(), c.diameter()))
                    : new SentDesign("X-" + out.size(), out.size() % 2 == 0 ? "" : "pa 1 cooler", size.count(), shape, null));
        }
        return out;
    }

    /**
     * Every size the stage proposes lands on the draft as the equipment it is (plan section 3.10: the type travels with the size): its kind,
     * a drum's orientation, its sizes, support, an air cooler's bank, a drum's boot and its outlet. The draft's place and paint stay; a column
     * lands as the calculator's design did, its skirt at least its least.
     */
    @Test
    void everySentSizeLandsAsTheEquipmentItIs() {
        for (var sent : sent()) {
            var landed = DRAFT.landed(sent);
            var built = landed.shape(net.minecraft.core.Direction.SOUTH);
            var shape = sent.shape();
            String what = sent.tag() + " " + shape.kind();
            assertEquals(DRAFT.placement(), landed.placement(), what);
            assertEquals(DRAFT.paint(), landed.paint(), what);
            assertEquals(shape.kind(), built.kind(), what);
            assertEquals(shape.orientation(), built.orientation(), what);
            assertEquals(shape.diameter(), built.diameter(), 1e-9, what);
            if (sent.column() != null) {
                assertEquals(sent.column(), landed.column(), what);
                assertEquals(Math.max(DRAFT.support(), EquipmentShape.minimumSkirt(shape.diameter())), built.support(), what);
                continue;
            }
            assertEquals(shape.tangentLength(), built.tangentLength(), 1e-9, what);
            assertEquals(shape.support(), built.support(), what);
            assertEquals(shape.bank(), built.bank(), what);
            assertEquals(shape.boot(), built.boot(), what);
            assertEquals(shape.outlet(), built.outlet(), what);
            assertEquals(DRAFT.column(), landed.column(), "the draft keeps its imported column for the column form");
        }
        // A lying drum laid crosswise stays crosswise.
        var lying = sent().stream().filter(s -> s.shape().kind() == EquipmentShape.Kind.DRUM && s.shape().orientation().horizontal()).findFirst().orElseThrow();
        var crosswise = DRAFT.landed(lying).withTurns(List.of());
        var turned = new Design(crosswise.diameter(), crosswise.tangentLength(), VesselPlacement.Form.CROSSWISE, crosswise.support(), crosswise.placement(), crosswise.trays(),
                crosswise.connections(), crosswise.paint(), crosswise.bank(), crosswise.boot(), crosswise.outlet(), crosswise.column(), crosswise.turns(), crosswise.towerBank());
        assertEquals(VesselPlacement.Form.CROSSWISE, turned.landed(lying).form());
    }

    /**
     * The answer's design tags (what {@link ImportAnswer} carries) read back on the client as the server wrote them, with
     * no repair. The answer's message is Minecraft's component codec, which reaches the item registry a unit test lacks.
     */
    @Test
    void anAnsweredDesignReadsBackAsTheServerWroteIt() {
        var tag = new CompoundTag();
        EquipmentConstructorBlockEntity.writeDesign(tag, DRAFT);
        var repairs = new ArrayList<String>();
        assertEquals(DRAFT, EquipmentConstructorBlockEntity.readDesign(tag, Design.DEFAULT, repairs::add));
        assertEquals(List.of(), repairs);
    }

    /** N1: a design's name crosses in its tags, a constructor saved before names reads unnamed, and one that breaks the rule is cleaned, a repair. */
    @Test
    void aDesignsNameReadsBackAndASavedOneBeforeNamesReadsUnnamed() {
        var named = DRAFT.withName("E-101");
        var tag = new CompoundTag();
        EquipmentConstructorBlockEntity.writeDesign(tag, named);
        var repairs = new ArrayList<String>();
        assertEquals(named, EquipmentConstructorBlockEntity.readDesign(tag, Design.DEFAULT, repairs::add));
        assertEquals(List.of(), repairs);
        tag.remove("Name");
        assertEquals("", EquipmentConstructorBlockEntity.readDesign(tag, Design.DEFAULT, repairs::add).name());
        assertEquals(List.of(), repairs);
        var unnamed = new CompoundTag();
        EquipmentConstructorBlockEntity.writeDesign(unnamed, DRAFT);
        assertFalse(unnamed.contains("Name"), "an unnamed design writes no name");
        tag.putString("Name", "  §cE-1\u000701  ");
        assertEquals("E-101", EquipmentConstructorBlockEntity.readDesign(tag, Design.DEFAULT, repairs::add).name());
        assertEquals(1, repairs.size(), repairs.toString());
    }

    /** N1: an import names the draft; a hand edit keeps the name, as it keeps the imported column; a new import replaces it. */
    @Test
    void anImportNamesTheDraftAndAHandEditKeepsIt() {
        var sent = new SentDesign("E-101", "", 1, EquipmentShape.designed(EquipmentShape.Kind.EXCHANGER, 1.5, 5, EquipmentShape.Heading.NORTH, 2,
                com.wormzjl.icheme.science.equipment.ProcessUnit.Bank.ONE, 0), null);
        var landed = DRAFT.landed(sent).withName("E-101");
        assertEquals("E-101", landed.name());
        assertEquals("E-101", landed.keepingColumn(DRAFT).name(), "a hand edit keeps the name");
        assertEquals("E-101", landed.withTurns(List.of()).name());
        assertEquals("E-101", landed.withTowerBank(com.wormzjl.icheme.science.equipment.TowerBank.ONE).name());
        assertEquals("E-102", landed.landed(sent).withName("E-102").name(), "a new import replaces it");
        assertEquals("", Design.DEFAULT.name(), "a design built by hand starts unnamed");
    }

    /** N1: a rename crosses whole, at most {@link EquipmentNetwork.Rename#MAX_TEXT} characters on the wire. */
    @Test
    void aRenameCrosses() {
        var rename = new EquipmentNetwork.Rename(AT, "P-101 水泵");
        assertEquals(rename, roundTrip(EquipmentNetwork.Rename.STREAM_CODEC, rename));
        assertThrows(Exception.class, () -> roundTrip(EquipmentNetwork.Rename.STREAM_CODEC, new EquipmentNetwork.Rename(AT, "x".repeat(EquipmentNetwork.Rename.MAX_TEXT + 1))));
    }
}

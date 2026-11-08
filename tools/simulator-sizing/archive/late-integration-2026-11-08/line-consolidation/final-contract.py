from pathlib import Path
r=Path('src/main/java/com/wormzjl/icheme')
p=r/'network/LineNetwork.java';s=p.read_text().replace('ComponentSerialization.STREAM_CODEC.encode(b,r.message());','b.writeUtf(net.minecraft.network.chat.Component.Serializer.toJson(r.message(),b.registryAccess()),8192);').replace('ComponentSerialization.STREAM_CODEC.decode(b)','Objects.requireNonNull(net.minecraft.network.chat.Component.Serializer.fromJson(b.readUtf(8192),b.registryAccess()))');p.write_text(s)
p=r/'world/level/block/LineLaying.java';s=p.read_text().replace('public record Result(UUID id,boolean success,Component message,int cells){}','public record Result(UUID id,boolean success,Component message,int cells){public Result{Objects.requireNonNull(id);Objects.requireNonNull(message);if(cells<0||cells>PipeRoute.Limits.DEFAULT.cells())throw new IllegalArgumentException("Invalid line result");}}');p.write_text(s)
p=Path('src/test/java/com/wormzjl/icheme/world/level/block/LineContractTest.java');s=p.read_text();i=s.index('    private static LineBackend.Host host(');s=s[:i]+'''    @Test void domainAdaptersCannotRegainCommandOrRouteOwnership() throws Exception {
        var root=Path.of("src/main/java/com/wormzjl/icheme");
        for(var name:List.of("FluidLineBackend","ConduitLineBackend")){
            var source=Files.readString(root.resolve("world/level/block/"+name+".java"));
            for(var forbidden:List.of("PipeRoute.search(","LinePlacement.commit(","canInteractWithBlock(","getInventory(","destroyBlock("))assertFalse(source.contains(forbidden),name+" must leave admission and geometry to the shared owner");
        }
        for(var name:List.of("PipeLayingClient","CableLayingClient")){
            var source=Files.readString(root.resolve("client/"+name+".java"));assertTrue(source.contains("LineLaying.search("));
            assertFalse(source.contains("PipeRoute.hostJoints("));assertFalse(source.contains("LinePlacement.search("));
        }
        assertFalse(Files.exists(root.resolve("world/level/block/CableLaying.java")));
    }
'''+s[i:];p.write_text(s)

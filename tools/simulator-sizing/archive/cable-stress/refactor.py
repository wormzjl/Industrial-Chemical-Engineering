from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/power/PowerNetwork.java')
s=p.read_text(encoding='utf-8')
s=s.replace('    private boolean transferring;\n    private int rotation;','    private PowerGrid grid;')
s=s.replace('private PowerNetwork(MinecraftServer server){this.server=server;}','private PowerNetwork(MinecraftServer server){this.server=server;grid=new PowerGrid(bus,ports,this::energy);}')
s=s.replace('ports=facts.ports();bus=new DataBus(facts.cables(),ports,CableMedium.POWER);revision=facts.topologyRevision();','ports=facts.ports();bus=new DataBus(facts.cables(),ports,CableMedium.POWER);grid=new PowerGrid(bus,ports,this::energy);revision=facts.topologyRevision();')
a=s.index('    private record Endpoint(')
s=s[:a]+'''    private IEnergyStorage energy(PowerGrid.Address address){
        var key=ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(address.dimension()));var level=server.getLevel(key);
        if(level==null||!level.hasChunkAt(address.owner())||level.getBlockEntity(address.owner()) instanceof ConduitBlockEntity)return null;
        return level.getCapability(Capabilities.EnergyStorage.BLOCK,address.owner(),address.face());
    }
    public void tick(){refresh();grid.tick();}
    /** No local buffer or alternate energy unit: neighbouring mods see the connected FE stores directly. */
    public IEnergyStorage capability(DataBus.Site site){
        return new IEnergyStorage(){
            private int transfer(int amount,boolean simulate,boolean input){owned();refresh();return grid.transfer(site,amount,simulate,input);}
            @Override public int receiveEnergy(int amount,boolean simulate){return transfer(amount,simulate,true);}
            @Override public int extractEnergy(int amount,boolean simulate){return transfer(amount,simulate,false);}
            @Override public int getEnergyStored(){owned();refresh();return grid.total(site,false);}
            @Override public int getMaxEnergyStored(){owned();refresh();return grid.total(site,true);}
            @Override public boolean canExtract(){owned();refresh();return grid.available(site,false);}
            @Override public boolean canReceive(){owned();refresh();return grid.available(site,true);}
        };
    }
}
'''
p.write_text(s,encoding='utf-8')

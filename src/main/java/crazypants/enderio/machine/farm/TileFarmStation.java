package crazypants.enderio.machine.farm;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.command.IEntitySelector;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.EnumSkyBlock;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ForgeDirection;
import cpw.mods.fml.common.network.NetworkRegistry.TargetPoint;
import crazypants.enderio.Config;
import crazypants.enderio.EnderIO;
import crazypants.enderio.ModObject;
import crazypants.enderio.machine.AbstractPoweredTaskEntity;
import crazypants.enderio.machine.IMachineRecipe;
import crazypants.enderio.machine.IMachineRecipe.ResultStack;
import crazypants.enderio.machine.IPoweredTask;
import crazypants.enderio.machine.SlotDefinition;
import crazypants.render.BoundingBox;
import crazypants.util.BlockCoord;
import crazypants.util.ItemUtil;
import crazypants.util.Util;

public class TileFarmStation extends AbstractPoweredTaskEntity implements IEntitySelector {

  private static final float ENERGY_PER_TICK = Config.farmContinuousEnergyUse;

  private BlockCoord lastScanned;
  private EntityPlayerMP farmerJoe;

  private int farmSize = Config.farmDefaultSize;

  private int minToolSlot = 0;
  private int maxToolSlot = 1;

  private int minSupSlot = maxToolSlot + 1;
  private int maxSupSlot = minSupSlot + 4;

  boolean isActive = false;
  boolean wasActive = false;

  private static final DummyTask TASK = new DummyTask();

  public TileFarmStation() {
    super(new SlotDefinition(6, 4, 0));
    currentTask = TASK;
  }

  public boolean hasHoe() {
    return hasTool(ItemHoe.class);
  }

  public boolean hasAxe() {
    return hasTool(ItemAxe.class);
  }

  public int geAxeLootingValue() {
    ItemStack tool = getTool(ItemAxe.class);
    if(tool == null) {
      return 0;
    }
    return getLooting(tool);
  }

  private int getLooting(ItemStack stack) {
    return Math.max(
        EnchantmentHelper.getEnchantmentLevel(Enchantment.looting.effectId, stack),
        EnchantmentHelper.getEnchantmentLevel(Enchantment.fortune.effectId, stack));
  }

  public void actionPerformed() {
    usePower(Config.farmActionEnergyUse);
  }

  public void damageAxe() {
    damageTool(ItemAxe.class, 1);
  }

  public void damageHoe(int i) {
    damageTool(ItemHoe.class, i);
  }

  private boolean hasTool(Class<? extends Item> class1) {
    return getTool(class1) != null;
  }

  private ItemStack getTool(Class<? extends Item> class1) {
    for (int i = minToolSlot; i <= maxToolSlot; i++) {
      if(Util.isType(inventory[i], class1)) {
        return inventory[i];
      }
    }
    return null;
  }

  private void destroyTool(Class<? extends Item> class1) {
    for (int i = minToolSlot; i <= maxToolSlot; i++) {
      if(Util.isType(inventory[i], class1)) {
        inventory[i] = null;
        markDirty();
        return;
      }
    }

  }

  private void damageTool(Class<? extends Item> class1, int damage) {
    ItemStack tool = getTool(class1);
    if(tool != null) {
      tool.damageItem(damage, farmerJoe);
      if(tool.getItemDamage() >= tool.getMaxDamage()) {
        destroyTool(class1);
      }
    }
  }



  public EntityPlayerMP getFakePlayer() {
    return farmerJoe;
  }

  public int getMaxLootingValue() {
    int result = 0;
    for (int i = minToolSlot; i <= maxToolSlot; i++) {
      if(inventory[i] != null) {
        int level = getLooting(inventory[i]);
        if(level > result) {
          result = level;
        }
      }
    }
    return result;
  }

  public void damageMaxLootingItem() {
    int maxLooting = 0;
    ItemStack toDamage = null;
    for (int i = minToolSlot; i <= maxToolSlot; i++) {
      if(inventory[i] != null) {
        int level = getLooting(inventory[i]);
        if(level > maxLooting) {
          maxLooting = level;
          toDamage = inventory[i];
        }
      }
    }
    if(toDamage != null) {
      toDamage.damageItem(1, farmerJoe);
    }
  }

  public Block getBlock(BlockCoord bc) {
    return worldObj.getBlock(bc.x, bc.y, bc.z);
  }

  public int getBlockMeta(BlockCoord bc) {
    return worldObj.getBlockMetadata(bc.x, bc.y, bc.z);
  }

  @Override
  protected boolean isMachineItemValidForSlot(int i, ItemStack stack) {
    if(stack == null) {
      return false;
    }
    if(i < 2) {
      return Util.isType(stack, ItemHoe.class) || Util.isType(stack, ItemAxe.class) || getLooting(stack) > 0;
    }
    return FarmersComune.instance.canPlant(stack);
  }

  @Override
  protected boolean checkProgress(boolean redstoneChecksPassed) {
    return super.checkProgress(redstoneChecksPassed) || doTick(redstoneChecksPassed);
  }

  protected boolean doTick(boolean redstoneCheckPassed) {

    if(!redstoneCheckPassed || !hasPower()) {
      return false;
    }
    //    if(worldObj.getWorldTime() % 4 != 0) {
    //      return false;
    //    }

    BlockCoord bc = getNextCoord();
    if(bc != null && bc.equals(getLocation())) { //don't try and harvest ourselves
      bc = getNextCoord();
    }
    if(bc == null) {
      return false;
    }    
    lastScanned = bc;

    Block block = worldObj.getBlock(bc.x, bc.y, bc.z);
    if(block == null) {
      return false;
    }
    int meta = worldObj.getBlockMetadata(bc.x, bc.y, bc.z);
    if(farmerJoe == null) {
      farmerJoe = new FakeFarmPlayer(MinecraftServer.getServer().worldServerForDimension(worldObj.provider.dimensionId));
    }

    if(block == Blocks.air) {
      FarmersComune.instance.prepareBlock(this, bc, block, meta);
      block = worldObj.getBlock(bc.x, bc.y, bc.z);
    }
    if(block != Blocks.air && hasPower()) {
      IHarvestResult harvest = FarmersComune.instance.harvestBlock(this, bc, block, meta);
      if(harvest != null) {
        if(harvest.getDrops() != null) {
          PacketFarmAction pkt = new PacketFarmAction(harvest.getHarvestedBlocks());
          EnderIO.packetPipeline.sendToAllAround(pkt, new TargetPoint(worldObj.provider.dimensionId, bc.x, bc.y, bc.z, 64));
          for (EntityItem ei : harvest.getDrops()) {
            if(ei != null) {
              worldObj.spawnEntityInWorld(ei);
            }
          }
        }
      }
    }
    return false;
  }

  public boolean hasSeed(ItemStack seeds, BlockCoord bc) {
    int slot = getSupplySlotForCoord(bc);
    ItemStack inv = inventory[slot];
    if(inv != null && inv.isItemEqual(seeds)) {
      return true;
    }
    return false;
  }

  public ItemStack getSeedFromSupplies(ItemStack stack, BlockCoord forBlock) {
    return getSeedFromSupplies(stack, forBlock, true);
  }

  public ItemStack getSeedFromSupplies(ItemStack stack, BlockCoord forBlock, boolean matchMetadata) {
    if(stack == null || forBlock == null) {
      return null;
    }
    int slot = getSupplySlotForCoord(forBlock);
    ItemStack inv = inventory[slot];
    if(inv != null) {
      if(matchMetadata ? inv.isItemEqual(stack) : inv.getItem() == stack.getItem()) {
        ItemStack result = inv.copy();
        result.stackSize = 1;

        inv.stackSize--;
        if(inv.stackSize == 0) {
          inv = null;
        }
        setInventorySlotContents(slot, inv);
        return result;
      }
    }
    return null;
  }

  protected int getSupplySlotForCoord(BlockCoord forBlock) {

    if(forBlock.x <= xCoord && forBlock.z > zCoord) {
      return minSupSlot;
    } else if(forBlock.x > xCoord && forBlock.z > zCoord - 1) {
      return minSupSlot + 1;
    } else if(forBlock.x < xCoord && forBlock.z <= zCoord) {
      return minSupSlot + 2;
    }
    return minSupSlot + 3;
  }

  private void doHoover() {

    BoundingBox bb = new BoundingBox(getLocation());
    AxisAlignedBB aabb = AxisAlignedBB.getBoundingBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    aabb = aabb.expand(farmSize + 3, farmSize + 3, farmSize + 3);
    List<EntityItem> interestingItems = worldObj.selectEntitiesWithinAABB(EntityItem.class, aabb, this);

    for (EntityItem entity : interestingItems) {
      double x = (xCoord + 0.5D - entity.posX);
      double y = (yCoord + 1D - entity.posY);
      double z = (zCoord + 0.5D - entity.posZ);

      double distance = Math.sqrt(x * x + y * y + z * z);
      if(distance < 1.25) {
        hooverEntity(entity);
      } else {
        double speed = 0.035;
        entity.motionX += x / distance * speed;
        entity.motionY += y * speed;
        if(y > 0) {
          entity.motionY = 0.12;
        }
        entity.motionZ += z / distance * speed;
      }

    }
  }

  private boolean isFull() {
    for (int i = minSupSlot; i <= maxSupSlot; i++) {
      ItemStack stack = inventory[i];
      if(stack == null || stack.stackSize < stack.getMaxStackSize()) {
        return false;
      }
    }
    for (int i = slotDefinition.getMinOutputSlot(); i <= slotDefinition.getMaxOutputSlot(); i++) {
      ItemStack stack = inventory[i];
      if(stack == null || stack.stackSize < stack.getMaxStackSize()) {
        return false;
      }
    }
    return true;
  }

  private void hooverEntity(Entity entity) {
    if(!worldObj.isRemote) {
      if(entity instanceof EntityItem && !entity.isDead) {
        EntityItem item = (EntityItem) entity;
        ItemStack stack = item.getEntityItem().copy();

        int numInserted = insertResult(stack);
        stack.stackSize -= numInserted;
        item.setEntityItemStack(stack);
        if(stack.stackSize == 0) {
          item.setDead();
        }
      }
    }

  }

  private int insertResult(ItemStack stack) {

    int origSize = stack.stackSize;
    stack = stack.copy();

    //try and place in the inputs first to restock
    int inserted = ItemUtil.doInsertItem(this, stack, ForgeDirection.UNKNOWN);
    if(inserted >= origSize) {
      return origSize;
    }
    stack.stackSize -= inserted;
    ResultStack[] in = new ResultStack[] { new ResultStack(stack) };
    mergeResults(in);
    return origSize - (in[0].item == null ? 0 : in[0].item.stackSize);

  }

  @Override
  public boolean isEntityApplicable(Entity entity) {
    if(entity.isDead) {
      return false;
    }
    if(entity instanceof IProjectile) {
      return entity.motionY < 0.01;
    }
    if(entity instanceof EntityItem) {
      ItemStack stack = ((EntityItem) entity).getEntityItem();
      return true;
    }
    return false;
  }

  private BlockCoord getNextCoord() {

    BlockCoord loc = getLocation();
    if(lastScanned == null) {
      lastScanned = new BlockCoord(loc.x - farmSize, loc.y, loc.z - farmSize);
      return lastScanned;
    }

    int nextX = lastScanned.x + 1;
    int nextZ = lastScanned.z;
    if(nextX > loc.x + farmSize) {
      nextX = loc.x - farmSize;
      nextZ += 1;
      if(nextZ > loc.z + farmSize) {
        lastScanned = null;
        return getNextCoord();
      }
    }
    return new BlockCoord(nextX, lastScanned.y, nextZ);
  }

  @Override
  public String getInventoryName() {
    return EnderIO.blockFarmStation.getLocalizedName();
  }

  @Override
  public boolean hasCustomInventoryName() {
    return false;
  }

  @Override
  public String getMachineName() {
    return ModObject.blockFarmStation.unlocalisedName;
  }

  @Override
  public float getProgress() {
    return 0.5f;
  }

  @Override
  public void updateEntity() {

    super.updateEntity();
    if(wasActive != isActive) {
      worldObj.updateLightByType(EnumSkyBlock.Block, xCoord, yCoord, zCoord);
    }
    wasActive = isActive;

    if(!isFull() && isActive()) {
      doHoover();
    }
  }

  @Override
  public float getPowerUsePerTick() {
    return ENERGY_PER_TICK;
  }

  @Override
  public void readCustomNBT(NBTTagCompound nbtRoot) {
    super.readCustomNBT(nbtRoot);
    currentTask = TASK;
    isActive = nbtRoot.getBoolean("isActive");
  }

  @Override
  public void writeCustomNBT(NBTTagCompound nbtRoot) {
    super.writeCustomNBT(nbtRoot);
    nbtRoot.setBoolean("isActive", isActive());
  }

  private static class DummyTask implements IPoweredTask {
    @Override
    public void writeToNBT(NBTTagCompound nbtRoot) {
    }

    @Override
    public void update(float availableEnergy) {
    }

    @Override
    public boolean isComplete() {
      return false;
    }

    @Override
    public float getRequiredEnergy() {
      return ENERGY_PER_TICK;
    }

    @Override
    public IMachineRecipe getRecipe() {
      return null;
    }

    @Override
    public float getProgress() {
      return 0.5f;
    }

    @Override
    public ResultStack[] getCompletedResult() {
      return new ResultStack[0];
    }

    @Override
    public float getChance() {
      return 1;
    }
  }

}

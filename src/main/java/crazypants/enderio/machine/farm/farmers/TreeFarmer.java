package crazypants.enderio.machine.farm.farmers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.util.ForgeDirection;
import crazypants.enderio.config.Config;
import crazypants.enderio.machine.farm.TileFarmStation;
import crazypants.util.BlockCoord;

public class TreeFarmer implements IFarmerJoe {

  private static final HeightComparator comp = new HeightComparator();

  protected Block sapling;
  protected ItemStack saplingItem;
  protected Block[] woods;
  
  protected TreeHarvestUtil harvester = new TreeHarvestUtil();
  private boolean ignoreMeta;

  public TreeFarmer(Block sapling, Block... wood) {
    this.sapling = sapling;
    if(sapling != null) {
      saplingItem = new ItemStack(sapling);
    }
    woods = wood;
  }

  public TreeFarmer(boolean ignoreMeta, Block sapling, Block... wood) {
    this(sapling,wood);
    this.ignoreMeta = ignoreMeta;
  }

  @Override
  public boolean canHarvest(TileFarmStation farm, BlockCoord bc, Block block, int meta) {
    return isWood(block);
  }

  protected boolean isWood(Block block) {
    for(Block wood : woods) {
      if(block == wood) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean canPlant(ItemStack stack) {
    return stack != null && stack.getItem() == saplingItem.getItem();
  }

  @Override
  public boolean prepareBlock(TileFarmStation farm, BlockCoord bc, Block block, int meta) {
    if(block == sapling) {
      return true;
    }
    return plantFromInventory(farm, bc, block, meta);
  }

  protected boolean plantFromInventory(TileFarmStation farm, BlockCoord bc, Block block, int meta) {
    World worldObj = farm.getWorldObj();
    if(canPlant(worldObj, bc)) {
      ItemStack seed = farm.takeSeedFromSupplies(saplingItem, bc, false);
      if(seed != null) {
        return plant(farm, worldObj, bc, seed);
      }
    }
    return false;
  }

  protected boolean canPlant(World worldObj, BlockCoord bc) {
    Block ground = worldObj.getBlock(bc.x, bc.y - 1, bc.z);
    IPlantable plantable = (IPlantable) sapling;
    if(sapling.canPlaceBlockAt(worldObj, bc.x, bc.y, bc.z) &&
        sapling.canBlockStay(worldObj, bc.x, bc.y, bc.z) &&
        ground.canSustainPlant(worldObj, bc.x, bc.y - 1, bc.z, ForgeDirection.UP, plantable)) {
      return true;
    }
    return false;
  }

  protected boolean plant(TileFarmStation farm, World worldObj, BlockCoord bc, ItemStack seed) {
    worldObj.setBlock(bc.x, bc.y, bc.z, Blocks.air, 0, 1 | 2);
    if(canPlant(worldObj, bc)) {
      worldObj.setBlock(bc.x, bc.y, bc.z, sapling, seed.getItemDamage(), 1 | 2);
      farm.actionPerformed(false);
      return true;
    }
    return false;
  }

  @Override
  public IHarvestResult harvestBlock(TileFarmStation farm, BlockCoord bc, Block block, int meta) {

    HarvestResult res = new HarvestResult();
    if(!farm.hasAxe()) {
      return res;
    }
    harvester.harvest(farm, this, bc, res);
    Collections.sort(res.harvestedBlocks, comp);

    List<BlockCoord> actualHarvests = new ArrayList<BlockCoord>();

    for (int i = 0; i < res.harvestedBlocks.size() && farm.hasAxe(); i++) {
      BlockCoord coord = res.harvestedBlocks.get(i);
      Block blk = farm.getBlock(coord);

      ArrayList<ItemStack> drops = blk.getDrops(farm.getWorldObj(), bc.x, bc.y, bc.z, farm.getBlockMeta(coord), farm.getAxeLootingValue());
      if(drops != null) {
        for (ItemStack drop : drops) {
          res.drops.add(new EntityItem(farm.getWorldObj(), bc.x + 0.5, bc.y + 0.5, bc.z + 0.5, drop.copy()));
        }
      }
      boolean isWood = true;
      if(!isWood(blk)) { //leaves
        isWood = Config.farmAxeDamageOnLeafBreak;
        int leaveMeta = farm.getBlockMeta(coord);
        if(TreeHarvestUtil.canDropFood(blk, leaveMeta)) {
          res.drops.add(TreeHarvestUtil.dropFoodAsItemWithChance(farm.getWorldObj(), bc, blk, leaveMeta));
        }
      } 
      farm.actionPerformed(isWood);      
      if(isWood) {
        farm.damageAxe(blk, coord);
      }
      farm.getWorldObj().setBlockToAir(coord.x, coord.y, coord.z);
      actualHarvests.add(coord);
    }
    
    if (!farm.hasAxe()) {
      farm.setNotification(TileFarmStation.NOTIFICATION_NO_AXE);
    }
    
    res.harvestedBlocks.clear();
    res.harvestedBlocks.addAll(actualHarvests);

    return res;
  }

  public boolean getIgnoreMeta()
  {
    return ignoreMeta;
  }

  private static class HeightComparator implements Comparator<BlockCoord> {

    @Override
    public int compare(BlockCoord o1, BlockCoord o2) {
      return compare(o2.y, o1.y); //reverse order
    }

    //same as 1.7 Integer.compare
    public static int compare(int x, int y) {
      return (x < y) ? -1 : ((x == y) ? 0 : 1);
    }
  }

}

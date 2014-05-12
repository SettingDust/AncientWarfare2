package net.shadowmage.ancientwarfare.automation.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.scoreboard.Team;
import net.minecraft.tileentity.TileEntity;
import net.shadowmage.ancientwarfare.automation.container.ContainerWarehouseControl;
import net.shadowmage.ancientwarfare.core.config.AWLog;
import net.shadowmage.ancientwarfare.core.interfaces.IBoundedTile;
import net.shadowmage.ancientwarfare.core.interfaces.IInteractableTile;
import net.shadowmage.ancientwarfare.core.interfaces.IOwnable;
import net.shadowmage.ancientwarfare.core.interfaces.IWorkSite;
import net.shadowmage.ancientwarfare.core.interfaces.IWorker;
import net.shadowmage.ancientwarfare.core.inventory.InventoryBasic;
import net.shadowmage.ancientwarfare.core.network.NetworkHandler;
import net.shadowmage.ancientwarfare.core.util.BlockPosition;
import net.shadowmage.ancientwarfare.core.util.InventoryTools;
import net.shadowmage.ancientwarfare.core.util.ItemQuantityMap;

public class WorkSiteWarehouse extends TileEntity implements IWorkSite, IInteractableTile, IBoundedTile, IOwnable
{

/**************************WORKSITE FIELDS******************************/
private BlockPosition bbMin;
private BlockPosition bbMax;
private int maxWorkers;
private String owningPlayer;
private Set<IWorker> workers = Collections.newSetFromMap( new WeakHashMap<IWorker, Boolean>());

/**************************WAREHOUSE FIELDS******************************/
private boolean init = false;
private List<IWarehouseStorageTile> storageTiles = new ArrayList<IWarehouseStorageTile>();
private List<TileWarehouseInput> inputTiles = new ArrayList<TileWarehouseInput>();
private List<TileWarehouseOutput> outputTiles = new ArrayList<TileWarehouseOutput>();
private Set<TileEntity> tilesToUpdate = new HashSet<TileEntity>();
private List<ContainerWarehouseControl> viewers = new ArrayList<ContainerWarehouseControl>();
public InventoryBasic inventory = new InventoryBasic(9);//manual input/output inventory
public ItemQuantityMap inventoryMap = new ItemQuantityMap(); 

private List<TileWarehouseOutput> outputToFill = new ArrayList<TileWarehouseOutput>();
private List<TileWarehouseInput> inputToEmpty = new ArrayList<TileWarehouseInput>();

int currentItemCount;//used slots--calced from item quantity map
int currentMaxItemCount;//max number of slots -- calced from storage blocks

public WorkSiteWarehouse()
  {
  bbMin = new BlockPosition();
  bbMax = new BlockPosition();
  maxWorkers = 3;
  }

@Override
public final boolean canUpdate()
  {
  return true;
  }

@Override
public void updateEntity()
  {
  if(!init)
    {
    init = true;
    scanInitialBlocks();  
    }
  if(!tilesToUpdate.isEmpty())
    {
    for(TileEntity te : tilesToUpdate)
      {
      if(te instanceof TileWarehouseInput)
        {
        updateInputTile((TileWarehouseInput) te);
        }
      else if(te instanceof TileWarehouseOutput)
        {
        updateOutputTile((TileWarehouseOutput)te);
        }
      }
    tilesToUpdate.clear();
    }
  }


/************************************************ MULTIBLOCK SYNCH METHODS *************************************************/

public void addInputBlock(TileWarehouseInput input)
  {
  if(!inputTiles.contains(input))
    {
    inputTiles.add(input);
    tilesToUpdate.add(input);
    }
  }

public void removeInputBlock(TileWarehouseInput input)
  {
  while(inputTiles.contains(input))
    {
    inputTiles.remove(input);
    }
  while(inputToEmpty.contains(input))
    {
    inputToEmpty.remove(input);
    }
  updateViewers();
  }

public List<TileWarehouseInput> getInputTiles()
  {
  return inputTiles;
  }

public void addStorageBlock(IWarehouseStorageTile tile)
  {
  if(!storageTiles.contains(tile))
    {
    storageTiles.add(tile);  
    currentMaxItemCount+=tile.getStorageAdditionSize();
    AWLog.logDebug("updated warehouse storage size to: "+currentMaxItemCount);
    }
  updateViewers();
  }

public void removeStorageBlock(IWarehouseStorageTile tile)
  {
  while(storageTiles.contains(tile))
    {
    storageTiles.remove(tile);    
    currentMaxItemCount-=tile.getStorageAdditionSize();
    AWLog.logDebug("updated warehouse storage size to: "+currentMaxItemCount);
    }
  updateViewers();
  }

public List<IWarehouseStorageTile> getStorageTiles()
  {
  return storageTiles;
  }

public void addOutputBlock(TileWarehouseOutput te)
  {
  if(!outputTiles.contains(te))
    {
    outputTiles.add(te);
    tilesToUpdate.add(te);
    }
  }

public void removeOutputBlock(TileWarehouseOutput te)
  {
  while(outputTiles.contains(te))
    {
    outputTiles.remove(te);
    }
  while(outputToFill.contains(te))
    {
    outputToFill.remove(te);
    }
  }

public List<TileWarehouseOutput> getOutputTiles()
  {
  return outputTiles;
  }

@Override
public void invalidate()
  {  
  super.invalidate();
  init = false;
  IControlledTile ict;
  for(TileWarehouseInput tile : this.inputTiles)
    {
    tile.setControllerPosition(null);
    }
  for(TileWarehouseOutput tile : this.outputTiles)
    {
    tile.setControllerPosition(null);
    }
  for(IWarehouseStorageTile tile : this.storageTiles)
    {
    if(tile instanceof IControlledTile)
      {
      ict = (IControlledTile)tile;
      ict.setControllerPosition(null);
      }
    }
  }

@Override
public void validate()
  {  
  super.validate();
  init = false;
  }

/**
 * should be called when tile is first loaded from disk, after world is set
 */
protected void scanInitialBlocks()
  {
  TileEntity te;
  for(int x = bbMin.x; x<=bbMax.x; x++)
    {
    for(int z = bbMin.z; z<=bbMax.z; z++)
      {
      for(int y = bbMin.y; y<=bbMax.y; y++)
        {
        if(!worldObj.blockExists(x, y, z)){continue;}
        te = worldObj.getTileEntity(x, y, z);
        if(te==null){continue;}
        else if(te instanceof IWarehouseStorageTile)
          {
          addStorageBlock((IWarehouseStorageTile) te);
          if(te instanceof IControlledTile)
            {
            ((IControlledTile) te).setControllerPosition(new BlockPosition(xCoord, yCoord, zCoord));
            }
          }
        else if(te instanceof TileWarehouseInput)
          {
          addInputBlock((TileWarehouseInput) te);
          if(te instanceof IControlledTile)
            {
            ((IControlledTile) te).setControllerPosition(new BlockPosition(xCoord, yCoord, zCoord));
            }
          }
        }
      }
    }
  }

/************************************************ INVENTORY TRACKING METHODS *************************************************/

public void onInputInventoryUpdated(TileWarehouseInput tile)
  {
  if(inputTiles.contains(tile))
    {
    tilesToUpdate.add(tile);    
    }
  }

public void onOutputInventoryUpdated(TileEntity tile)
  {
  if(outputTiles.contains(tile))
    {
    tilesToUpdate.add(tile);    
    }
  }

public void requestItem(ItemStack filter)
  {
  int quantity = inventoryMap.getCount(filter);
  if(quantity>filter.getMaxStackSize())
    {
    quantity = filter.getMaxStackSize();
    }
  if(quantity<=0){return;}
  ItemStack toMerge = filter.copy();
  toMerge.stackSize = quantity;
  inventoryMap.decreaseCount(filter, quantity);
  toMerge = InventoryTools.mergeItemStack(inventory, toMerge, -1);
  if(toMerge!=null)
    {
    inventoryMap.addCount(toMerge, toMerge.stackSize);
    }
  }

public void updateInputTile(TileWarehouseInput tile)
  {
  inputToEmpty.remove(tile);
  ItemStack item;
  for(int i = 0; i < tile.getSizeInventory(); i++)
    {
    item = tile.getStackInSlot(i);
    if(item!=null)
      {
      inputToEmpty.add(tile);
      break;
      }
    }
  }

public void updateOutputTile(TileWarehouseOutput tile)
  {
  outputToFill.remove(tile);//remove it in case it was already present in the toFil set
  List<WarehouseItemFilter> filters = tile.getFilters();
  for(WarehouseItemFilter filter : filters)
    {
    if(InventoryTools.getCountOf(tile, -1, filter.getFilterItem())<filter.getFilterQuantity())
      {
      outputToFill.add(tile);
      break;
      }
    }  
  }

public void updateSlotCount()
  {
  this.currentItemCount = inventoryMap.getTotalItemCount();
  }

/************************************************ WORKSITE METHODS *************************************************/

@Override
public WorkType getWorkType()
  {
  return WorkType.CRAFTING;
  }

@Override
public boolean onBlockClicked(EntityPlayer player)
  {
  if(!player.worldObj.isRemote)
    {
    NetworkHandler.INSTANCE.openGui(player, NetworkHandler.GUI_WAREHOUSE_CONTROL, xCoord, yCoord, zCoord);    
    }
  return true;
  }

@Override
public final boolean addWorker(IWorker worker)
  {
  if(!worker.getWorkTypes().contains(getWorkType()) || worker.getTeam() != this.getTeam())
    {
    return false;
    }
  if(workers.size()<maxWorkers || workers.contains(worker))
    {
    workers.add(worker);
    return true;
    }
  return false;
  }

@Override
public final void removeWorker(IWorker worker)
  {
  workers.remove(worker);
  }

@Override
public final boolean hasWorkBounds()
  {
  return bbMin !=null || (bbMin!=null && bbMax!=null);
  }

@Override
public final BlockPosition getWorkBoundsMin()
  {
  return bbMin;
  }

@Override
public final BlockPosition getWorkBoundsMax()
  {
  return bbMax;
  }

@Override
public final Team getTeam()
  {  
  if(owningPlayer!=null)
    {
    worldObj.getScoreboard().getPlayersTeam(owningPlayer);
    }
  return null;
  }

@Override
public List<BlockPosition> getWorkTargets()
  {
  return Collections.emptyList();
  }

public final void setOwnerName(String name)
  {
  this.owningPlayer = name;
  }

@Override
public void setBounds(BlockPosition p1, BlockPosition p2)
  {
  bbMin = p1;
  bbMax = p2;
  }

@Override
public boolean hasWork()
  {
  return (!inputToEmpty.isEmpty() && currentItemCount<currentMaxItemCount) || !outputToFill.isEmpty();
  }

@Override
public void doWork(IWorker worker)
  {
  processWork();  
  }

@Override
public void doPlayerWork(EntityPlayer player)
  {
  processWork();
  }

private void processWork()
  {
  long t1 = System.nanoTime();  
  if(!inputToEmpty.isEmpty())
    {
    processInputWork();
    }
  else if(!outputToFill.isEmpty())
    {
    processOutputWork();
    }
  long t2 = System.nanoTime();
  long t3 = (t2-t1);
  float f1 = (float)((double)t3 / 1000000.d);
  AWLog.logDebug("work time: "+(t2-t1)+"ns ("+f1+"ms)");
  updateViewers();
  }

private void processInputWork()
  {
  TileWarehouseInput tile;
  outerLoopLabel:
  while(!inputToEmpty.isEmpty())
    {
    tile = inputToEmpty.remove(0);
    ItemStack stack;
    int transferQuantity;
    for(int i=0; i<tile.getSizeInventory(); i++)
      {
      stack = tile.getStackInSlot(i);
      if(stack!=null)
        {
        transferQuantity = currentMaxItemCount-currentItemCount;
        if(transferQuantity>stack.stackSize)
          {
          transferQuantity=stack.stackSize;
          }
        inventoryMap.addCount(stack, transferQuantity);
        stack.stackSize-=transferQuantity;
        currentItemCount+=transferQuantity;
        if(stack.stackSize<=0)
          {
          tile.setInventorySlotContents(i, null);
          }
        tilesToUpdate.add(tile);
        
        //if a non-null stack was found
        break outerLoopLabel;
        }
      }    
    tilesToUpdate.add(tile);
    }
  }

private void processOutputWork()
  {
  TileWarehouseOutput tile;
  List<WarehouseItemFilter> filters;
  ItemStack toMerge;
  int filterQuantity, foundQuantity, transferQuantity, passXfer;
  
  AWLog.logDebug("processing output work..1");
  
  outerLoopLabel:  
  while(!outputToFill.isEmpty())
    {
    AWLog.logDebug("processing output work..2");
    tile = outputToFill.remove(0);
    tilesToUpdate.add(tile);
    filters = tile.getFilters();
    for(WarehouseItemFilter filter : filters)
      {
      AWLog.logDebug("processing output work..3");
      if(filter.getFilterItem()==null){continue;}
      filterQuantity = filter.getFilterQuantity();
      foundQuantity = InventoryTools.getCountOf(tile, -1, filter.getFilterItem());
      if(foundQuantity<filterQuantity)
        {
        transferQuantity = inventoryMap.getCount(filter.getFilterItem());
        if(transferQuantity==0){continue;}
        if(transferQuantity > filterQuantity-foundQuantity)
          {
          transferQuantity = filterQuantity - foundQuantity;
          }
        while(transferQuantity>0)
          {
          AWLog.logDebug("processing output work..4");
          toMerge = filter.getFilterItem().copy();
          passXfer = transferQuantity;
          if(passXfer>toMerge.getMaxStackSize())
            {
            passXfer = toMerge.getMaxStackSize();
            }
          toMerge.stackSize = passXfer;
          transferQuantity -= passXfer;
          inventoryMap.decreaseCount(toMerge, passXfer);
          toMerge = InventoryTools.mergeItemStack(tile, toMerge, -1);
          if(toMerge!=null)//could only partially merge--perhaps output is full?
            {
            inventoryMap.addCount(toMerge, toMerge.stackSize);
            break;
            }
          }
        if(transferQuantity != filterQuantity-foundQuantity)//at least one item was merged, break completely out as work was done
          {
          break outerLoopLabel;
          }
        }
      }        
    }
  AWLog.logDebug("processed output work. output set:"+outputToFill+" update set: "+tilesToUpdate);
  }

/************************************************ NETWORK METHODS *************************************************/

public void addViewer(ContainerWarehouseControl viewer)
  {
  if(!viewers.contains(viewer))
    {
    viewers.add(viewer);
    }
  AWLog.logDebug("adding viewer... now contains:"+viewers);
  }

public void removeViewer(ContainerWarehouseControl viewer)
  {  
  while(viewers.contains(viewer))
    {
    viewers.remove(viewer);
    }
  AWLog.logDebug("removing viewer... now contains:"+viewers);
  }

public void updateViewers()
  {
  for(ContainerWarehouseControl container : this.viewers)
    {
    container.refreshGui();
    if(!worldObj.isRemote)
      {
      container.onWarehouseInventoryUpdated();
      }
    }
  }

@Override
public void readFromNBT(NBTTagCompound tag)
  {
  super.readFromNBT(tag);
  owningPlayer = tag.getString("owner");
  bbMin.read(tag.getCompoundTag("pos1"));
  bbMax.read(tag.getCompoundTag("pos2"));  
  if(tag.hasKey("inventory"))
    {
    inventory.readFromNBT(tag.getCompoundTag("inventory"));
    }
  if(tag.hasKey("itemMap"))
    {
    inventoryMap.readFromNBT(tag.getCompoundTag("itemMap"));
    }
  this.updateSlotCount();
  }

@Override
public void writeToNBT(NBTTagCompound tag)
  {
  super.writeToNBT(tag);
  tag.setString("owner", owningPlayer);
  tag.setTag("pos1", bbMin.writeToNBT(new NBTTagCompound()));
  tag.setTag("pos2", bbMax.writeToNBT(new NBTTagCompound()));
  tag.setTag("inventory", inventory.writeToNBT(new NBTTagCompound()));
  tag.setTag("itemMap", inventoryMap.writeToNBT(new NBTTagCompound()));
  }

@Override
public final Packet getDescriptionPacket()
  {
  NBTTagCompound tag = new NBTTagCompound();
  if(bbMin!=null)
    {
    NBTTagCompound innerTag = new NBTTagCompound();
    bbMin.writeToNBT(innerTag);
    tag.setTag("bbMin", innerTag);
    }
  if(bbMax!=null)
    {
    NBTTagCompound innerTag = new NBTTagCompound();
    bbMax.writeToNBT(innerTag);
    tag.setTag("bbMax", innerTag);
    }
  return new S35PacketUpdateTileEntity(this.xCoord, this.yCoord, this.zCoord, 3, tag);
  }

@Override
public final void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt)
  {
  NBTTagCompound tag = pkt.func_148857_g();
  if(tag.hasKey("bbMin"))
    {
    bbMin = new BlockPosition();
    bbMin.read(tag.getCompoundTag("bbMin"));
    }
  if(tag.hasKey("bbMax"))
    {
    bbMax = new BlockPosition();
    bbMax.read(tag.getCompoundTag("bbMax"));
    }
  }


}

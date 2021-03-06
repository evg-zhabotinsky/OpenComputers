package li.cil.oc.integration.computercraft

import dan200.computercraft.api.ComputerCraftAPI
import dan200.computercraft.api.redstone.IBundledRedstoneProvider
import li.cil.oc.common.tileentity.traits.BundledRedstoneAware
import li.cil.oc.integration.util.BundledRedstone
import li.cil.oc.integration.util.BundledRedstone.RedstoneProvider
import li.cil.oc.util.BlockPosition
import net.minecraft.world.World
import net.minecraft.util.EnumFacing
import net.minecraft.util.math.BlockPos

object BundledRedstoneProvider extends IBundledRedstoneProvider with RedstoneProvider {
  def init() {
    ComputerCraftAPI.registerBundledRedstoneProvider(this)
    BundledRedstone.addProvider(this)
  }

  override def getBundledRedstoneOutput(world: World, blockPos: BlockPos, enumFacing: EnumFacing): Int =
    world.getTileEntity(blockPos) match {
      case tile: BundledRedstoneAware =>
        var result = 0
        val colors = tile.getBundledOutput(enumFacing)
        for (color <- 0 to 15) {
          if (colors(color) > 0) result |= 1 << color
        }
        result
      case _ => -1
    }

  override def computeInput(pos: BlockPosition, side: EnumFacing): Int = 0

  override def computeBundledInput(pos: BlockPosition, side: EnumFacing): Array[Int] = {
    val offset = pos.offset(side)
    val strength = ComputerCraftAPI.getBundledRedstoneOutput(pos.world.get, offset.toBlockPos, side.getOpposite)
    if (strength >= 0) {
      val strengths = new Array[Int](16)
      for (colour <- 0 to 15) {
        strengths(colour) = if ((strength & (1 << colour)) == 0) 0 else 15
      }
      strengths
    } else null
  }
}

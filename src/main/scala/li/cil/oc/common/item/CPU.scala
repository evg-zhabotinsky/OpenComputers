package li.cil.oc.common.item

import scala.language.existentials

class CPU(val parent: Delegator, val tier: Int) extends Delegate with ItemTier with traits.CPULike {
  override val unlocalizedName = super.unlocalizedName + tier

  override def cpuTier = tier

  override protected def tooltipName = Option(super.unlocalizedName)
}

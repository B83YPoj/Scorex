package scorex.transaction

import com.google.common.primitives.Ints
import scorex.serialization.JsonSerializable


/**
  * A transaction is an atomic state modifier
  */
trait Transaction extends StateChangeReason with JsonSerializable {

  val assetFee: (Option[AssetId], Long)
  val timestamp: Long

  def balanceChanges(): Seq[BalanceChange]

  val typeOrdering: Int = 0

  override def equals(other: Any): Boolean = other match {
    case tx: Transaction => id.sameElements(tx.id)
    case _ => false
  }

  override def hashCode(): Int = Ints.fromByteArray(id.takeRight(4))

}

case class BalanceChange(assetAcc: AssetAcc, delta: Long)

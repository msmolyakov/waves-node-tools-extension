package scorex.transaction

import com.google.common.primitives.Longs

case class FeesStateChange(fee: Long) extends StateChangeReason {
  override def bytes: Array[Byte] = Longs.toByteArray(fee)
}
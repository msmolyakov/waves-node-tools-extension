package im.mak.nodetools

import com.wavesplatform.account.KeyPair
import com.wavesplatform.common.utils.{Base58, _}
import com.wavesplatform.state.Blockchain
import com.wavesplatform.state.diffs.FeeValidation
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.TxValidationError.AlreadyInTheState
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.utx.UtxPool
import im.mak.nodetools.PayoutDB.{Payout, PayoutTransaction}
import im.mak.nodetools.settings.PayoutSettings

object Payouts {
  val genBalanceDepth: Int = sys.props.get("node-tools.gen-balance-depth").fold(1000)(_.toInt)

  def initPayouts(settings: PayoutSettings, blockchain: Blockchain, utx: UtxPool, minerKey: KeyPair)(
      implicit notifications: NotificationService
  ): Unit = {
    val currentHeight = blockchain.height
    if (!settings.enable || currentHeight < settings.fromHeight) return

    val last = PayoutDB.lastPayoutHeight()
    if ((currentHeight - last) < settings.interval) return

    val fromHeight = math.max(last + 1, settings.fromHeight)
    val toHeight   = currentHeight - 1
    if (toHeight < fromHeight) return

    val minerAddress = minerKey.toAddress
    val leases = blockchain
      .collectActiveLeases(1, toHeight) { lease =>
        blockchain.resolveAlias(lease.recipient).contains(minerAddress)
      }
      .map { lease =>
        val Some(height) = blockchain.transactionHeight(lease.id())
        (height, lease)
      }

    val generatingBalance = blockchain.balanceSnapshots(minerAddress, fromHeight, blockchain.lastBlockId.get).map(_.effectiveBalance).max
    val wavesReward       = PayoutDB.calculateReward(fromHeight, toHeight)

    if (wavesReward > 0) {
      val payout = PayoutDB.addPayout(fromHeight, toHeight, wavesReward, generatingBalance, leases)
      createPayoutTransactions(payout, leases.map(_._2), settings, utx, blockchain, minerKey)
      notifications.info(s"Registering payout [$fromHeight-$toHeight]: ${Format.waves(wavesReward)} Waves")
    }
  }

  private[this] def createPayoutTransactions(
      payout: Payout,
      leases: Seq[LeaseTransaction],
      settings: PayoutSettings,
      utx: UtxPool,
      blockchain: Blockchain,
      key: KeyPair
  ): Unit = {
    import scala.concurrent.duration._

    val total = payout.generatingBalance
    val transfers = leases.groupBy(_.sender).mapValues { leases =>
      val amount = leases.map(_.amount).sum
      val share  = amount.toDouble / total
      payout.amount.toDouble * share * settings.percent / 100
    }

    val allTransfers = transfers
      .mapValues(_.toLong)
      .collect { case (sender, amount) if amount > 0 => MassTransferTransaction.ParsedTransfer(sender.toAddress, amount.toLong) }
      .ensuring(_.map(_.amount).sum <= payout.amount, "Incorrect payments total amount")

    val timestamp = System.currentTimeMillis() + settings.delay.minutes.toMillis - 5000
    val transactions = allTransfers.toList
      .grouped(100)
      .map { txTransfers =>
        val transactionFee: Long = {
          val dummyTx = MassTransferTransaction(Asset.Waves, key, txTransfers, timestamp, 0, Array.emptyByteArray, Nil)
          FeeValidation.getMinFee(blockchain, blockchain.height, dummyTx).fold(_ => FeeValidation.FeeUnit * 2, _.minFeeInWaves)
        }

        MassTransferTransaction
          .selfSigned(Asset.Waves, key, txTransfers, timestamp, transactionFee, Array.emptyByteArray)
          .explicitGet()
      }
      .filter(_.transfers.nonEmpty)
      .toVector

    PayoutDB.addPayoutTransactions(payout.id, transactions)
  }

  def finishUnconfirmedPayouts(settings: PayoutSettings, utx: UtxPool, blockchain: Blockchain, key: KeyPair)(
      implicit notifications: NotificationService
  ): Unit = {
    def commitTx(transferTransaction: MassTransferTransaction): Unit = {
      utx.putIfNew(transferTransaction).resultE match {
        case Right(_) | Left(_: AlreadyInTheState) => notifications.info(s"Transaction sent: $transferTransaction")
        case Left(value)                           => notifications.error(s"Error sending transaction: $value (tx = $transferTransaction)")
      }
    }

    val unconfirmed = PayoutDB.unconfirmedTransactions(blockchain.height, settings.delay)
    unconfirmed foreach {
      case PayoutTransaction(txId, _, transaction, _) =>
        blockchain.transactionHeight(Base58.decode(txId)) match {
          case Some(txHeight) => PayoutDB.confirmTransaction(txId, txHeight)
          case None           => commitTx(transaction)
        }
    }
  }

  def registerBlock(height: Int, wavesReward: Long): Unit =
    PayoutDB.addMinedBlock(height, wavesReward)
}

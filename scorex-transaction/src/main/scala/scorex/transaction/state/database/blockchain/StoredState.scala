package scorex.transaction.state.database.blockchain

import org.h2.mvstore.MVStore
import play.api.libs.json.{JsNumber, JsObject}
import scorex.account.Account
import scorex.block.Block
import scorex.crypto.encode.Base58
import scorex.crypto.hash.FastCryptographicHash
import scorex.settings.WavesHardForkParameters
import scorex.transaction._
import scorex.transaction.assets._
import scorex.transaction.assets.exchange.OrderMatch
import scorex.transaction.state.database.state._
import scorex.utils.{NTP, ScorexLogging}

import scala.collection.SortedMap
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}


/**
  * Validation and processing of data with respect to the local storage
  *
  * Use fromDB method of StoredState object to create new instance
  */
class StoredState(minimalStorage: StateStorageI,
                  assetsExtension: AssetsExtendedState,
                  orderMatchExtension: OrderMatchStoredState,
                  settings: WavesHardForkParameters) extends LagonakiState with ScorexLogging {

  override def included(id: Array[Byte], heightOpt: Option[Int]): Option[Int] = minimalStorage.included(id, heightOpt)

  def stateHeight: Int = minimalStorage.stateHeight

  private def getIssueTransaction(assetId: AssetId): Option[IssueTransaction] =
    minimalStorage.getTransactionBytes(assetId).flatMap(b => IssueTransaction.parseBytes(b).toOption)

  def getAccountBalance(account: Account): Map[AssetId, (Long, Boolean, Long, IssueTransaction)] = {
    val address = account.address
    minimalStorage.getAccountAssets(address).foldLeft(Map.empty[AssetId, (Long, Boolean, Long, IssueTransaction)]) { (result, asset) =>
      val triedAssetId = Base58.decode(asset)
      val balance = balanceByKey(address + asset)

      if (triedAssetId.isSuccess) {
        val assetId = triedAssetId.get
        val maybeIssueTransaction = getIssueTransaction(assetId)
        if (maybeIssueTransaction.isDefined)
          result.updated(assetId, (balance, isAssetReissuable(assetId), totalAssetQuantity(assetId), maybeIssueTransaction.get))
        else result
      } else result
    }
  }

  private[blockchain] def applyChanges(changes: Map[AssetAcc, (AccState, Reason)], blockTs: Long = NTP.correctedTime()): Unit = synchronized {
    minimalStorage.setStateHeight(minimalStorage.stateHeight + 1)
    val h = minimalStorage.stateHeight
    changes.foreach { ch =>
      val change = Row(ch._2._1, ch._2._2, minimalStorage.getLastStates(ch._1.key).getOrElse(0))
      minimalStorage.putAccountChanges(ch._1.key, h, change)
      minimalStorage.putLastStates(ch._1.key, h)
      ch._2._2.foreach {
        case tx: Transaction => minimalStorage.putTransaction(tx, h)
        case _ =>
      }

      ch._2._2.foreach {
        case tx: AssetIssuance =>
          assetsExtension.addAsset(tx.assetId, h, tx.id, tx.quantity, tx.reissuable)
        case tx: BurnTransaction =>
          assetsExtension.burnAsset(tx.assetId, h, tx.id, -tx.amount)
        case om: OrderMatch =>
          orderMatchExtension.putOrderMatch(om, blockTs)
        case _ =>
      }
      minimalStorage.updateAccountAssets(ch._1.account.address, ch._1.assetId)
    }
  }

  def rollbackTo(rollbackTo: Int): State = synchronized {
    def deleteNewer(key: Address): Unit = {
      val currentHeight = minimalStorage.getLastStates(key).getOrElse(0)
      if (currentHeight > rollbackTo) {
        val changes = minimalStorage.removeAccountChanges(key, currentHeight)
        changes.reason.foreach(t => {
          minimalStorage.removeTx(t.id)
          t match {
            case t: AssetIssuance =>
              assetsExtension.rollbackTo(t.assetId, currentHeight)
            case t: BurnTransaction =>
              assetsExtension.rollbackTo(t.assetId, currentHeight)
            case _ =>
          }
        })
        val prevHeight = changes.lastRowHeight
        minimalStorage.putLastStates(key, prevHeight)
        deleteNewer(key)
      }
    }

    minimalStorage.lastStatesKeys.foreach { key =>
      deleteNewer(key)
    }
    minimalStorage.setStateHeight(rollbackTo)
    this
  }

  override def processBlock(block: Block): Try[State] = Try {
    val trans = block.transactions
    val fees: Map[AssetAcc, (AccState, Reason)] = block.consensusModule.feesDistribution(block)
      .map(m => m._1 -> (AccState(assetBalance(m._1) + m._2), List(FeesStateChange(m._2))))

    log.debug(s"${block.timestampField.value} < ${settings.allowTemporaryNegativeUntil} = ${block.timestampField.value < settings.allowTemporaryNegativeUntil}")
    val newBalances: Map[AssetAcc, (AccState, Reason)] = calcNewBalances(trans, fees, block.timestampField.value < settings.allowTemporaryNegativeUntil)
    newBalances.foreach(nb => require(nb._2._1.balance >= 0))

    applyChanges(newBalances, block.timestampField.value)
    log.trace(s"New state height is ${minimalStorage.stateHeight}, hash: $hash, totalBalance: $totalBalance")

    this
  }


  private[blockchain] def calcNewBalances(trans: Seq[Transaction], fees: Map[AssetAcc, (AccState, Reason)], allowTemporaryNegative: Boolean):
  Map[AssetAcc, (AccState, Reason)] = {
    val newBalances: Map[AssetAcc, (AccState, Reason)] = trans.foldLeft(fees) { case (changes, tx) =>
      tx.balanceChanges().foldLeft(changes) { case (iChanges, bc) =>
        //update balances sheet
        val currentChange = iChanges.getOrElse(bc.assetAcc, (AccState(assetBalance(bc.assetAcc)), List.empty))
        val newBalance = if (currentChange._1.balance == Long.MinValue) Long.MinValue
        else Try(Math.addExact(currentChange._1.balance, bc.delta)).getOrElse(Long.MinValue)

        if (newBalance < 0 && !allowTemporaryNegative) {
          throw new Error(s"Transaction leads to negative balance ($newBalance): ${tx.json}")
        }

        iChanges.updated(bc.assetAcc, (AccState(newBalance), tx +: currentChange._2))
      }
    }
    newBalances
  }

  private[blockchain] def filterValidTransactions(trans: Seq[Transaction]):
  Seq[Transaction] = {
    trans.foldLeft((Map.empty[AssetAcc, (AccState, Reason)], Seq.empty[Transaction])) {
      case ((currentState, validTxs), tx) =>
        try {
          val newState = tx.balanceChanges().foldLeft(currentState) { case (iChanges, bc) =>
            //update balances sheet
            val currentChange = iChanges.getOrElse(bc.assetAcc, (AccState(assetBalance(bc.assetAcc)), List.empty))
            val newBalance = if (currentChange._1.balance == Long.MinValue) Long.MinValue
            else Try(Math.addExact(currentChange._1.balance, bc.delta)).getOrElse(Long.MinValue)

            if (newBalance < 0 && tx.timestamp >= settings.allowTemporaryNegativeUntil) {
              throw new Error(s"Transaction leads to negative balance ($newBalance): ${tx.json}")
            }

            iChanges.updated(bc.assetAcc, (AccState(newBalance), tx +: currentChange._2))
          }
          (newState, validTxs :+ tx)
        } catch {
          case NonFatal(e) =>
            (currentState, validTxs)
        }
    }._2
  }

  override def balance(account: Account, atHeight: Option[Int] = None): Long =
    assetBalance(AssetAcc(account, None), atHeight)

  def assetBalance(account: AssetAcc, atHeight: Option[Int] = None): Long = {
    balanceByKey(account.key, atHeight)
  }

  def isAssetReissuable(assetId: AssetId): Boolean = assetsExtension.isReissuable(assetId)

  def totalAssetQuantity(assetId: AssetId): Long = assetsExtension.getAssetQuantity(assetId)

  override def balanceWithConfirmations(account: Account, confirmations: Int, heightOpt: Option[Int]): Long =
    balance(account, Some(Math.max(1, heightOpt.getOrElse(minimalStorage.stateHeight) - confirmations)))

  private def balanceByKey(key: String, atHeight: Option[Int] = None): Long = {
    minimalStorage.getLastStates(key) match {
      case Some(h) if h > 0 =>
        val requiredHeight = atHeight.getOrElse(minimalStorage.stateHeight)
        require(requiredHeight >= 0, s"Height should not be negative, $requiredHeight given")

        def loop(hh: Int, min: Long = Long.MaxValue): Long = {
          //TODO .get ???
          val row = minimalStorage.getAccountChanges(key, hh).get
          require(Option(row).isDefined, s"accountChanges($key).get($hh) is null. lastStates.get(address)=$h")
          if (hh <= requiredHeight) Math.min(row.state.balance, min)
          else if (row.lastRowHeight == 0) 0L
          else loop(row.lastRowHeight, Math.min(row.state.balance, min))
        }

        loop(h)
      case _ =>
        0L
    }
  }

  def totalBalance: Long = minimalStorage.lastStatesKeys.map(address => balanceByKey(address)).sum

  private val DefaultLimit = 50

  override def accountTransactions(account: Account, limit: Int = DefaultLimit): Seq[Transaction] = {
    val accountAssets = minimalStorage.getAccountAssets(account.address)
    val keys = account.address :: accountAssets.map(account.address + _).toList

    def getTxSize(m: SortedMap[Int, Seq[Transaction]]) = m.foldLeft(0)((size, txs) => size + txs._2.size)

    def getRowTxs(row: Row) = row.reason.filter(_.isInstanceOf[Transaction]).map(_.asInstanceOf[Transaction])

    keys.foldLeft(SortedMap.empty[Int, Seq[Transaction]]) { (result, key) =>

      minimalStorage.getLastStates(key) match {
        case Some(accHeight) if getTxSize(result) < limit || accHeight > result.firstKey =>
          def loop(h: Int, acc: SortedMap[Int, Seq[Transaction]]): SortedMap[Int, Seq[Transaction]] = {
            minimalStorage.getAccountChanges(key, h) match {
              case Some(row) =>
                val rowTxs = getRowTxs(row)
                val resAcc = acc + (h -> (rowTxs ++ acc.getOrElse(h, Seq.empty[Transaction])))
                if (getTxSize(resAcc) < limit) {
                  loop(row.lastRowHeight, resAcc)
                } else {
                  if (row.lastRowHeight > resAcc.firstKey) loop(row.lastRowHeight, resAcc.tail)
                  else resAcc
                }
              case _ => acc
            }
          }
          loop(accHeight, result)
        case _ => result
      }

    }.values.flatten.toList.sortWith(_.timestamp > _.timestamp).take(limit)
  }

  def lastAccountPaymentTransaction(account: Account): Option[PaymentTransaction] = {
    def loop(h: Int, address: Address): Option[PaymentTransaction] = {
      minimalStorage.getAccountChanges(address, h) match {
        case Some(row) =>
          val accountTransactions = row.reason
            .filter(_.isInstanceOf[PaymentTransaction])
            .map(_.asInstanceOf[PaymentTransaction])
            .filter(_.sender.address == address)
          if (accountTransactions.nonEmpty) Some(accountTransactions.maxBy(_.timestamp))
          else loop(row.lastRowHeight, address)
        case _ => None
      }
    }

    minimalStorage.getLastStates(account.address) match {
      case Some(height) => loop(height, account.address)
      case None => None
    }
  }

  /**
    * Returns sequence of valid transactions
    */
  override final def validate(trans: Seq[Transaction], heightOpt: Option[Int] = None, blockTime: Long): Seq[Transaction] = {
    val height = heightOpt.getOrElse(minimalStorage.stateHeight)

    val txs = trans.filter(t => isValid(t, height))
    val allowInvalidPaymentTransactionsByTimestamp = txs.nonEmpty && txs.map(_.timestamp).max < settings.allowInvalidPaymentTransactionsByTimestamp
    val validTransactions = if (allowInvalidPaymentTransactionsByTimestamp) {
      txs
    } else {
      val invalidPaymentTransactionsByTimestamp = invalidatePaymentTransactionsByTimestamp(txs)
      excludeTransactions(txs, invalidPaymentTransactionsByTimestamp)
    }

    val allowTransactionsFromFutureByTimestamp = validTransactions.nonEmpty && validTransactions.map(_.timestamp).max < settings.allowTransactionsFromFutureUntil
    val filteredFromFuture = if (allowTransactionsFromFutureByTimestamp) {
      validTransactions
    } else {
      filterTransactionsFromFuture(validTransactions, blockTime)
    }

    val allowUnissuedAssets = filteredFromFuture.nonEmpty && txs.map(_.timestamp).max < settings.allowUnissuedAssetsUntil

    def filterValidTransactionsByState(trans: Seq[Transaction]): Seq[Transaction] = {
      val (state, validTxs) = trans.foldLeft((Map.empty[AssetAcc, (AccState, Reason)], Seq.empty[Transaction])) {
        case ((currentState, seq), tx) =>
          try {
            val changes = if (allowUnissuedAssets) tx.balanceChanges() else tx.balanceChanges().sortBy(_.delta)
            val newState = changes.foldLeft(currentState) { case (iChanges, bc) =>
              //update balances sheet
              def safeSum(first: Long, second: Long): Long = {
                try {
                  Math.addExact(first, second)
                } catch {
                  case e: ArithmeticException =>
                    throw new Error(s"Transaction leads to overflow balance: $first + $second = ${first + second}")
                }
              }

              val currentChange = iChanges.getOrElse(bc.assetAcc, (AccState(assetBalance(bc.assetAcc)), List.empty))
              val newBalance = safeSum(currentChange._1.balance, bc.delta)
              if (newBalance >= 0 || tx.timestamp < settings.allowTemporaryNegativeUntil) {
                iChanges.updated(bc.assetAcc, (AccState(newBalance), tx +: currentChange._2))
              } else {
                throw new Error(s"Transaction leads to negative state: ${currentChange._1.balance} + ${bc.delta} = ${currentChange._1.balance + bc.delta}")
              }
            }
            (newState, seq :+ tx)
          } catch {
            case NonFatal(e) =>
              (currentState, seq)
          }
      }
      validTxs
    }

    filterValidTransactionsByState(filteredFromFuture)
  }

  private def excludeTransactions(transactions: Seq[Transaction], exclude: Iterable[Transaction]) =
    transactions.filter(t1 => !exclude.exists(t2 => t2.id sameElements t1.id))

  private def invalidatePaymentTransactionsByTimestamp(transactions: Seq[Transaction]): Seq[Transaction] = {
    val paymentTransactions = transactions.filter(_.isInstanceOf[PaymentTransaction])
      .map(_.asInstanceOf[PaymentTransaction])

    val initialSelection: Map[String, (List[Transaction], Long)] = Map(paymentTransactions.map { payment =>
      val address = payment.sender.address
      val stateTimestamp = lastAccountPaymentTransaction(payment.sender) match {
        case Some(lastTransaction) => lastTransaction.timestamp
        case _ => 0
      }
      address -> (List[Transaction](), stateTimestamp)
    }: _*)

    val orderedTransaction = paymentTransactions.sortBy(_.timestamp)
    val selection: Map[String, (List[Transaction], Long)] = orderedTransaction.foldLeft(initialSelection) { (s, t) =>
      val address = t.sender.address
      val tuple = s(address)
      if (t.timestamp > tuple._2) {
        s.updated(address, (tuple._1, t.timestamp))
      } else {
        s.updated(address, (tuple._1 :+ t, tuple._2))
      }
    }

    selection.foldLeft(List[Transaction]()) { (l, s) => l ++ s._2._1 }
  }

  private def filterTransactionsFromFuture(transactions: Seq[Transaction], blockTime: Long): Seq[Transaction] = {
    transactions.filter {
      tx => (tx.timestamp - blockTime).millis <= SimpleTransactionModule.MaxTimeForUnconfirmed
    }
  }

  private[blockchain] def isValid(transaction: Transaction, height: Int): Boolean = transaction match {
    case tx: PaymentTransaction =>
      transaction.timestamp < settings.allowInvalidPaymentTransactionsByTimestamp ||
        (transaction.timestamp >= settings.allowInvalidPaymentTransactionsByTimestamp && isTimestampCorrect(tx))
    case tx: TransferTransaction =>
      included(tx.id, None).isEmpty
    case tx: IssueTransaction =>
      included(tx.id, None).isEmpty
    case tx: ReissueTransaction =>
      val reissueValid: Boolean = {
        val sameSender = isIssuerAddress(tx.assetId, tx.sender.address)
        val reissuable = assetsExtension.isReissuable(tx.assetId)
        sameSender && reissuable
      }
      reissueValid && included(tx.id, None).isEmpty
    case tx: BurnTransaction =>
      tx.timestamp > settings.allowBurnTransactionAfterTimestamp &&
        isIssuerAddress(tx.assetId, tx.sender.address) && included(tx.id, None).isEmpty
    case tx: OrderMatch =>
      orderMatchExtension.isOrderMatchValid(tx) && included(tx.id, None).isEmpty
    case gtx: GenesisTransaction =>
      height == 0
    case otx: Any =>
      log.error(s"Wrong kind of tx: $otx")
      false
  }

  private def isIssuerAddress(assetId: Array[Byte], address: String): Boolean = {
    minimalStorage.getTransactionBytes(assetId).exists(b =>
      IssueTransaction.parseBytes(b) match {
        case Success(issue) =>
          issue.sender.address == address
        case Failure(f) =>
          log.debug(s"Can't deserialise issue tx", f)
          false
      })
  }

  private def isTimestampCorrect(tx: PaymentTransaction): Boolean = {
    lastAccountPaymentTransaction(tx.sender) match {
      case Some(lastTransaction) => lastTransaction.timestamp < tx.timestamp
      case None => true
    }
  }

  //for debugging purposes only
  def toJson(heightOpt: Option[Int] = None): JsObject = {
    val ls = minimalStorage.lastStatesKeys.map(add => add -> balanceByKey(add, heightOpt))
      .filter(b => b._2 != 0).sortBy(_._1)
    JsObject(ls.map(a => a._1 -> JsNumber(a._2)).toMap)
  }

  //for debugging purposes only
  override def toString: String = toJson().toString()

  def hash: Int = {
    (BigInt(FastCryptographicHash(toString.getBytes)) % Int.MaxValue).toInt
  }

}

object StoredState {
  def fromDB(db: MVStore, settings: WavesHardForkParameters): StoredState = {
    val min = new MVStoreStateStorage(db)
    new StoredState(min, new AssetsExtendedState(db), new OrderMatchStoredState(db, min), settings)
  }

}
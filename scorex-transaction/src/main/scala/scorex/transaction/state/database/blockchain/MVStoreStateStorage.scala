package scorex.transaction.state.database.blockchain

import org.h2.mvstore.{MVMap, MVStore}
import scorex.crypto.encode.Base58
import scorex.transaction._
import scorex.transaction.state.database.state._
import scorex.utils.LogMVMapBuilder

import scala.collection.JavaConversions._


class MVStoreStateStorage(val db: MVStore) extends StateStorageI {


  // ============= Account Changes
  private val AccountChanges = "AccountChanges"
  private val accountChanges: MVMap[String, Row] =
    db.openMap(AccountChanges, new LogMVMapBuilder[String, Row].valueType(RowDataType))

  private def acKey(address: Address, height: Int): String = height + "-" + address

  def getAccountChanges(key: Address, height: Int): Option[Row] = Option(accountChanges.get(acKey(key, height)))

  def putAccountChanges(key: Address, height: Int, data: Row): Unit = accountChanges.put(acKey(key, height), data)

  def removeAccountChanges(key: Address, height: Int): Row = accountChanges.remove(acKey(key, height))


  // ============= Last States
  private val LastStates = "lastStates"
  private val lastStates: MVMap[Address, Int] = db.openMap(LastStates, new LogMVMapBuilder[Address, Int])

  def getLastStates(a: Address): Option[Int] = Option(lastStates.get(a))

  def putLastStates(a: Address, s: Int): Unit = lastStates.put(a, s)

  def lastStatesKeys: List[Address] = lastStates.keySet().toList


  // ============= Last States
  private val AccountAssets = "accountAssets"
  private val accountAssetsMap: MVMap[String, Set[String]] = db.openMap(AccountAssets,
    new LogMVMapBuilder[String, Set[String]])

  def updateAccountAssets(address: Address, assetId: Option[AssetId]): Unit = {
    if (assetId.isDefined) {
      val asset = Base58.encode(assetId.get)
      val assets = Option(accountAssetsMap.get(address)).getOrElse(Set.empty[String])
      accountAssetsMap.put(address, assets + asset)
    }
  }

  def getAccountAssets(address: Address): Set[String] =
    Option(accountAssetsMap.get(address)).getOrElse(Set.empty[String])


  // ============= transactions
  private val AllTxs = "IssueTxs"
  private val IncludedTx = "includedTx"

  /**
    * Transaction ID -> serialized transaction
    */
  private val transactionsMap: MVMap[Array[Byte], Array[Byte]] = db.openMap(AllTxs, new LogMVMapBuilder[Array[Byte], Array[Byte]])
  /**
    * Transaction ID -> Block height
    */
  private val includedTx: MVMap[Array[Byte], Int] = db.openMap(IncludedTx, new LogMVMapBuilder[Array[Byte], Int])

  override def getTransactionBytes(id: Array[Byte]): Option[Array[Byte]] = Option(transactionsMap.get(id))

  override def putTransaction(tx: Transaction, height: Int): Unit = {
    includedTx.put(tx.id, height)
    transactionsMap.put(tx.id, tx.bytes)
  }

  override def removeTx(id: Array[Byte]): Unit = {
    includedTx.remove(id)
    transactionsMap.remove(id)
  }

  override def included(id: Array[Byte], heightOpt: Option[Int]): Option[Int] =
    Option(includedTx.get(id)).filter(_ < heightOpt.getOrElse(Int.MaxValue))


  // ============= state height
  private val HeightKey = "height"
  private val heightMap: MVMap[String, Int] = db.openMap(HeightKey, new LogMVMapBuilder[String, Int])
  if (Option(heightMap.get(HeightKey)).isEmpty) heightMap.put(HeightKey, 0)

  def stateHeight: Int = heightMap.get(HeightKey)

  def setStateHeight(height: Int): Unit = heightMap.put(HeightKey, height)


  if (db.getStoreVersion > 0) db.rollback()

}

package scorex.transaction.state.database.blockchain

import scorex.transaction.Transaction

trait StateExtension {
  def isValid(tx: Transaction): Boolean

  def process(tx: Transaction, blockTs: Long): Unit

}

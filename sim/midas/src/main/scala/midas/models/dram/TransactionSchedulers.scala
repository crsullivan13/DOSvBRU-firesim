package midas
package models

import chisel3._
import chisel3.util._
import junctions._
import midas.widgets._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util.DecoupledHelper

// Add some scheduler specific metadata to a reference
class XactionSchedulerEntry(implicit p: Parameters) extends NastiBundle()(p) {
  val xaction = new TransactionMetaData
  val addr = UInt(nastiXAddrBits.W)
 }

class XactionSchedulerIO(val cfg: BaseConfig)(implicit val p: Parameters) extends Bundle{
  val req = Flipped(new NastiReqChannels)
  val nextXaction = Decoupled(new XactionSchedulerEntry)
  val pendingWReq = Input(UInt((cfg.maxWrites + 1).W))
  val pendingAWReq = Input(UInt((cfg.maxWrites + 1).W))
}

class SplitXactionSchedulerIO(cfg: BaseConfig)(implicit p: Parameters) extends XactionSchedulerIO(cfg)(p){
  //val readForwardXaction = Decoupled(new XactionSchedulerEntry)
  val writeSkip = Decoupled(new XactionSchedulerEntry) // respond immediately to recieved writes as they are complete once queued, i.e. don't wait till dequeue
  val doesSchedHavePendingReads = Input(Bool())
}

class UnifiedFIFOXactionScheduler(depth: Int, cfg: BaseConfig)(implicit p: Parameters) extends Module {
  val io = IO(new XactionSchedulerIO(cfg))

  import DRAMMasEnums._

  val transactionQueue = Module(new Queue(new XactionSchedulerEntry, depth))
  val transactionQueueArb = Module(new RRArbiter(new XactionSchedulerEntry, 2))

  transactionQueueArb.io.in(0).valid := io.req.ar.valid
  io.req.ar.ready := transactionQueueArb.io.in(0).ready
  transactionQueueArb.io.in(0).bits.xaction := TransactionMetaData(io.req.ar.bits)
  transactionQueueArb.io.in(0).bits.addr := io.req.ar.bits.addr

  transactionQueueArb.io.in(1).valid := io.req.aw.valid
  io.req.aw.ready := transactionQueueArb.io.in(1).ready
  transactionQueueArb.io.in(1).bits.xaction := TransactionMetaData(io.req.aw.bits)
  transactionQueueArb.io.in(1).bits.addr := io.req.aw.bits.addr

  transactionQueue.io.enq <> transactionQueueArb.io.out

  // Accept up to one additional write data request
  // TODO: More sensible model; maybe track a write buffer volume
  io.req.w.ready := io.pendingWReq <= io.pendingAWReq

  val selectedCmd = WireInit(cmd_nop)
  val completedWrites = SatUpDownCounter(cfg.maxWrites)
  completedWrites.inc := io.req.w.fire && io.req.w.bits.last
  completedWrites.dec := io.nextXaction.fire && io.nextXaction.bits.xaction.isWrite

  // Prevent release of oldest transaction if it is a write and it's data is not yet available
  val deqGate = DecoupledHelper(
    transactionQueue.io.deq.valid,
    io.nextXaction.ready,
    (!io.nextXaction.bits.xaction.isWrite || ~completedWrites.empty)
  )

  io.nextXaction <> transactionQueue.io.deq
  io.nextXaction.valid := deqGate.fire(io.nextXaction.ready)
  transactionQueue.io.deq.ready := deqGate.fire(transactionQueue.io.deq.valid)
}

class SplitXactionScheduler(depth: Int, cfg: BaseConfig)(implicit p: Parameters) extends Module {
  val io = IO(new SplitXactionSchedulerIO(cfg))

  import DRAMMasEnums._

  val readQueue = Module(new Queue(new XactionSchedulerEntry, depth))
  val writeQueue = Module(new Queue(new XactionSchedulerEntry, depth))
  val writeRespQueue = Module(new Queue(new XactionSchedulerEntry, depth))
  val transactionQueueArb = Module(new RRArbiter(new XactionSchedulerEntry, 2))

  val shouldDrainWrites = RegInit(false.B)

  transactionQueueArb.io.in(0).valid := io.req.ar.valid
  io.req.ar.ready := transactionQueueArb.io.in(0).ready
  transactionQueueArb.io.in(0).bits.xaction := TransactionMetaData(io.req.ar.bits)
  transactionQueueArb.io.in(0).bits.addr := io.req.ar.bits.addr

  transactionQueueArb.io.in(1).valid := io.req.aw.valid
  io.req.aw.ready := transactionQueueArb.io.in(1).ready && writeRespQueue.io.enq.ready
  transactionQueueArb.io.in(1).bits.xaction := TransactionMetaData(io.req.aw.bits)
  transactionQueueArb.io.in(1).bits.addr := io.req.aw.bits.addr

  writeRespQueue.io.enq.bits.xaction := TransactionMetaData(io.req.aw.bits)
  writeRespQueue.io.enq.bits.addr := io.req.aw.bits.addr
  writeRespQueue.io.enq.valid := io.req.aw.valid
  assert(writeRespQueue.io.count < depth.U)

  readQueue.io.enq.bits := transactionQueueArb.io.out.bits
  writeQueue.io.enq.bits := transactionQueueArb.io.out.bits
  transactionQueueArb.io.out.ready := Mux(transactionQueueArb.io.out.bits.xaction.isWrite, writeQueue.io.enq.ready, readQueue.io.enq.ready)
  writeQueue.io.enq.valid := transactionQueueArb.io.out.valid && transactionQueueArb.io.out.bits.xaction.isWrite
  readQueue.io.enq.valid := transactionQueueArb.io.out.valid && !transactionQueueArb.io.out.bits.xaction.isWrite

  when ( readQueue.io.enq.fire ) {
    printf("DRAM: enqueue a read\n")
  }

  when ( readQueue.io.deq.fire ) {
    printf("DRAM: dequeue a read\n")
  }

  when ( writeQueue.io.enq.fire ) {
    printf("DRAM: enqueue a write\n")
  }

  // Accept up to one additional write data request
  // TODO: More sensible model; maybe track a write buffer volume
  io.req.w.ready := io.pendingWReq <= io.pendingAWReq
  //io.req.w.ready := writeQueue.io.enq.ready

  println(s"DRAM: Max writes is ${cfg.maxWrites}")

  val selectedCmd = WireInit(cmd_nop)
  val completedWrites = SatUpDownCounter(cfg.maxWrites)
  completedWrites.inc := io.req.w.fire && io.req.w.bits.last
  completedWrites.dec := io.nextXaction.fire && io.nextXaction.bits.xaction.isWrite

  val ackedWrites = SatUpDownCounter(cfg.maxWrites)
  ackedWrites.inc := io.req.w.fire && io.req.w.bits.last
  ackedWrites.dec := writeRespQueue.io.deq.fire

  // TODO: Make watermarks a parameter
  val shouldUseHighWatermark = completedWrites.value >= ( depth.U - 8.U )
  val shouldUseLowWatermark = ( completedWrites.value >= 5.U ) && !io.doesSchedHavePendingReads
  val isDraining = shouldDrainWrites && ( completedWrites.value > 0.U )
  shouldDrainWrites := shouldUseHighWatermark || shouldUseLowWatermark || isDraining

    when ( !io.req.aw.ready && io.req.aw.valid ) {
    printf("DRAM: write queue is not ready, count is %d\n", completedWrites.value)
  }

  when ( writeQueue.io.deq.fire && shouldDrainWrites ) {
    printf("DRAM: dequeue writes, completed writes is %d\n", completedWrites.value)
  }

  // Prevent release of oldest transaction if it is a write and it's data is not yet available
  val deqGate = DecoupledHelper(
    readQueue.io.deq.valid,
    io.nextXaction.ready
  )

  val writeDeqGate = DecoupledHelper(
    writeQueue.io.deq.valid,
    shouldDrainWrites,
    io.nextXaction.ready,
    ~completedWrites.empty
  )

  when ( io.nextXaction.fire ) {
    printf("DRAM: nextxaction fired %d\n", io.nextXaction.bits.xaction.isWrite)
  }
  io.nextXaction.bits := Mux(shouldDrainWrites, writeQueue.io.deq.bits, readQueue.io.deq.bits)
  io.nextXaction.valid := Mux(shouldDrainWrites, writeDeqGate.fire(io.nextXaction.ready), deqGate.fire(io.nextXaction.ready) && !shouldDrainWrites)
  readQueue.io.deq.ready := deqGate.fire(readQueue.io.deq.valid) && !shouldDrainWrites
  writeQueue.io.deq.ready := writeDeqGate.fire(writeQueue.io.deq.valid)

  when ( io.writeSkip.fire ) {
    printf("DRAM: Write skip fired\n")
  }
  io.writeSkip.bits := writeRespQueue.io.deq.bits
  io.writeSkip.valid := writeRespQueue.io.deq.valid && ( ackedWrites.value > 0.U )
  writeRespQueue.io.deq.ready := io.writeSkip.ready && ( ackedWrites.value > 0.U )
}

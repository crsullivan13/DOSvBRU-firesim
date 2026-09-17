package midas
package models

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util.DecoupledHelper

import midas.widgets._

import firesim.lib.nasti._

// Add some scheduler specific metadata to a reference
class XactionSchedulerEntry(nastiParams: NastiParameters) extends NastiBundle(nastiParams) {
  val xaction = new TransactionMetaData(nastiParams)
  val addr    = UInt(nastiXAddrBits.W)
}

class XactionSchedulerIO(nastiParams: NastiParameters, val cfg: BaseConfig)(implicit p: Parameters) extends Bundle {
  val req          = Flipped(new NastiReqChannels(nastiParams))
  val nextXaction  = Decoupled(new XactionSchedulerEntry(nastiParams))
  val pendingWReq  = Input(UInt((cfg.maxWrites + 1).W))
  val pendingAWReq = Input(UInt((cfg.maxWrites + 1).W))
}

class UnifiedFIFOXactionScheduler(nastiParams: NastiParameters, depth: Int, cfg: BaseConfig)(implicit p: Parameters)
    extends Module {
  val io = IO(new XactionSchedulerIO(nastiParams, cfg))

  import DRAMMasEnums._

  val transactionQueue    = Module(new Queue(new XactionSchedulerEntry(nastiParams), depth))
  val transactionQueueArb = Module(new RRArbiter(new XactionSchedulerEntry(nastiParams), 2))

  transactionQueueArb.io.in(0).valid        := io.req.ar.valid
  io.req.ar.ready                           := transactionQueueArb.io.in(0).ready
  transactionQueueArb.io.in(0).bits.xaction := TransactionMetaData(nastiParams, io.req.ar.bits)
  transactionQueueArb.io.in(0).bits.addr    := io.req.ar.bits.addr

  transactionQueueArb.io.in(1).valid        := io.req.aw.valid
  io.req.aw.ready                           := transactionQueueArb.io.in(1).ready
  transactionQueueArb.io.in(1).bits.xaction := TransactionMetaData(nastiParams, io.req.aw.bits)
  transactionQueueArb.io.in(1).bits.addr    := io.req.aw.bits.addr

  transactionQueue.io.enq <> transactionQueueArb.io.out

  // Accept up to one additional write data request
  // TODO: More sensible model; maybe track a write buffer volume
  io.req.w.ready := io.pendingWReq <= io.pendingAWReq

  val selectedCmd     = WireInit(cmd_nop)
  val completedWrites = SatUpDownCounter(cfg.maxWrites)
  completedWrites.inc := io.req.w.fire && io.req.w.bits.last
  completedWrites.dec := io.nextXaction.fire && io.nextXaction.bits.xaction.isWrite

  // Prevent release of oldest transaction if it is a write and it's data is not yet available
  val deqGate = DecoupledHelper(
    transactionQueue.io.deq.valid,
    io.nextXaction.ready,
    (!io.nextXaction.bits.xaction.isWrite || ~completedWrites.empty),
  )

  io.nextXaction                <> transactionQueue.io.deq
  io.nextXaction.valid          := deqGate.fire(io.nextXaction.ready)
  transactionQueue.io.deq.ready := deqGate.fire(transactionQueue.io.deq.valid)
}

class SplitXactionSchedulerIO(nastiParams: NastiParameters, cfg: BaseConfig)(implicit p: Parameters)
    extends XactionSchedulerIO(nastiParams, cfg) {
  // Respond immediately to received writes: they are complete once queued, so
  // don't wait until they drain out of the write queue.
  val writeSkip                 = Decoupled(new XactionSchedulerEntry(nastiParams))
  val doesSchedHavePendingReads = Input(Bool())
}

class SplitXactionScheduler(
  nastiParams:            NastiParameters,
  depth:                  Int,
  cfg:                    BaseConfig,
  highWatermark:          Int,
  lowWatermark:           Int,
  opportunisticWatermark: Int,
)(implicit p:             Parameters
) extends Module {
  val io = IO(new SplitXactionSchedulerIO(nastiParams, cfg))

  import DRAMMasEnums._

  require(highWatermark > lowWatermark, s"highWatermark ($highWatermark) must exceed lowWatermark ($lowWatermark)")
  require(
    highWatermark <= cfg.maxWrites,
    s"highWatermark ($highWatermark) is unreachable: completedWrites saturates at maxWrites (${cfg.maxWrites})",
  )

  val readQueue           = Module(new Queue(new XactionSchedulerEntry(nastiParams), depth))
  val writeQueue          = Module(new Queue(new XactionSchedulerEntry(nastiParams), depth))
  val writeRespQueue      = Module(new Queue(new XactionSchedulerEntry(nastiParams), depth))
  val transactionQueueArb = Module(new RRArbiter(new XactionSchedulerEntry(nastiParams), 2))

  val shouldDrainWrites = RegInit(false.B)

  transactionQueueArb.io.in(0).valid        := io.req.ar.valid
  io.req.ar.ready                           := transactionQueueArb.io.in(0).ready
  transactionQueueArb.io.in(0).bits.xaction := TransactionMetaData(nastiParams, io.req.ar.bits)
  transactionQueueArb.io.in(0).bits.addr    := io.req.ar.bits.addr

  transactionQueueArb.io.in(1).valid        := io.req.aw.valid
  io.req.aw.ready                           := transactionQueueArb.io.in(1).ready
  transactionQueueArb.io.in(1).bits.xaction := TransactionMetaData(nastiParams, io.req.aw.bits)
  transactionQueueArb.io.in(1).bits.addr    := io.req.aw.bits.addr

  val enqWrite = DecoupledHelper(
    transactionQueueArb.io.out.valid,
    transactionQueueArb.io.out.bits.xaction.isWrite,
    writeQueue.io.enq.ready,
    writeRespQueue.io.enq.ready,
  )

  writeRespQueue.io.enq.bits  := transactionQueueArb.io.out.bits
  writeRespQueue.io.enq.valid := enqWrite.fire(writeRespQueue.io.enq.ready)

  readQueue.io.enq.bits            := transactionQueueArb.io.out.bits
  writeQueue.io.enq.bits           := transactionQueueArb.io.out.bits
  transactionQueueArb.io.out.ready := Mux(
    transactionQueueArb.io.out.bits.xaction.isWrite,
    writeQueue.io.enq.ready && writeRespQueue.io.enq.ready,
    readQueue.io.enq.ready,
  )
  writeQueue.io.enq.valid          := enqWrite.fire(writeQueue.io.enq.ready)
  readQueue.io.enq.valid           := transactionQueueArb.io.out.valid && !transactionQueueArb.io.out.bits.xaction.isWrite

  // Accept up to one additional write data request
  // TODO: More sensible model; maybe track a write buffer volume
  io.req.w.ready := io.pendingWReq <= io.pendingAWReq

  val selectedCmd     = WireInit(cmd_nop)
  val completedWrites = SatUpDownCounter(cfg.maxWrites)
  completedWrites.inc := io.req.w.fire && io.req.w.bits.last
  completedWrites.dec := io.nextXaction.fire && io.nextXaction.bits.xaction.isWrite

  val ackedWrites = SatUpDownCounter(cfg.maxWrites)
  ackedWrites.inc := io.req.w.fire && io.req.w.bits.last
  ackedWrites.dec := writeRespQueue.io.deq.fire

  val isOverHighWatermark = completedWrites.value >= highWatermark.U
  val opportunisticDrain  = (completedWrites.value >= opportunisticWatermark.U) && !io.doesSchedHavePendingReads
  val isDraining          = shouldDrainWrites && (completedWrites.value > lowWatermark.U)
  shouldDrainWrites := isOverHighWatermark || opportunisticDrain || isDraining

  // Prevent release of oldest transaction if it is a write and its data is not yet available
  val deqGate = DecoupledHelper(
    readQueue.io.deq.valid,
    io.nextXaction.ready,
  )

  val writeDeqGate = DecoupledHelper(
    writeQueue.io.deq.valid,
    shouldDrainWrites,
    io.nextXaction.ready,
    ~completedWrites.empty,
  )

  io.nextXaction.bits     := Mux(shouldDrainWrites, writeQueue.io.deq.bits, readQueue.io.deq.bits)
  io.nextXaction.valid    := Mux(
    shouldDrainWrites,
    writeDeqGate.fire(io.nextXaction.ready),
    deqGate.fire(io.nextXaction.ready) && !shouldDrainWrites,
  )
  readQueue.io.deq.ready  := deqGate.fire(readQueue.io.deq.valid) && !shouldDrainWrites
  writeQueue.io.deq.ready := writeDeqGate.fire(writeQueue.io.deq.valid)

  io.writeSkip.bits           := writeRespQueue.io.deq.bits
  io.writeSkip.valid          := writeRespQueue.io.deq.valid && (ackedWrites.value > 0.U)
  writeRespQueue.io.deq.ready := io.writeSkip.ready && (ackedWrites.value > 0.U)
}

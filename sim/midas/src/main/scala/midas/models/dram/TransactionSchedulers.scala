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

// Runtime write-drain controls, in units of pending writes (data resident, CAS not yet issued)
class WriteDrainSettings(val maxCount: Int) extends Bundle {
  val highWatermark = UInt(log2Ceil(maxCount + 1).W)
  val lowWatermark  = UInt(log2Ceil(maxCount + 1).W)
  val minBatch      = UInt(log2Ceil(maxCount + 1).W)
  val idleCycles    = UInt(WriteDrainSettings.idleCyclesWidth.W) // 0 disables the idle drain
}

object WriteDrainSettings {
  val idleCyclesWidth = 16
}

// Write-drain instrumentation, all free-running 32-bit counters
class WriteDrainStats extends Bundle {
  val drainsHigh          = UInt(32.W) // write-mode entries because pending writes reached highWatermark
  val drainsOpportunistic = UInt(32.W) // write-mode entries at lowWatermark with no reads pending
  val drainsIdle          = UInt(32.W) // write-mode entries below lowWatermark after idleCycles with no reads
  val writesDrained       = UInt(32.W) // CASW issued (all of them happen in write mode)
  val writeModeCycles     = UInt(32.W) // cycles in write mode
  val readsBlockedCycles  = UInt(32.W) // cycles in write mode while reads are pending
}

class SplitXactionSchedulerIO(nastiParams: NastiParameters, val cfg: BaseConfig, depth: Int, maxPending: Int)(
  implicit p: Parameters
) extends Bundle {
  val req          = Flipped(new NastiReqChannels(nastiParams))
  val pendingWReq  = Input(UInt((cfg.maxWrites + 1).W))
  val pendingAWReq = Input(UInt((cfg.maxWrites + 1).W))

  // Feed the read and write scheduling windows. Admission is not gated by the mode;
  // the timing model only issues commands for the window the mode selects.
  val nextRead  = Decoupled(new XactionSchedulerEntry(nastiParams))
  val nextWrite = Decoupled(new XactionSchedulerEntry(nastiParams))

  // Respond immediately to received writes: they are complete once queued, so
  // don't wait until they drain out of the write queue.
  val writeSkip = Decoupled(new XactionSchedulerEntry(nastiParams))

  // From the timing model
  val readsInWindow = Input(Bool()) // any read in the read window
  val writeIssued   = Input(Bool()) // a CASW was issued this cycle

  // To the timing model: true = issue only from the write window, false = only from the read window
  val writeMode      = Output(Bool())
  val pendingWrites  = Output(UInt(log2Ceil(2 * maxPending + 1).W)) // window + queued-with-data
  val drainSettings  = Input(new WriteDrainSettings(depth))
  val drainStats     = Output(new WriteDrainStats)
}

// Read and write transaction queues feeding separate scheduling windows, with a
// read/write mode that decides which window may issue (as in Ramulator's controller,
// where write mode selects the buffer the scheduler picks from).
//
// Mode switching, on pendingWrites = writes with data resident whose CASW has not issued:
//   read -> write: pendingWrites >= highWatermark, or pendingWrites >= lowWatermark and no
//                  reads are pending (opportunistic, threshold = low watermark), or any
//                  pendingWrites after idleCycles consecutive cycles with no reads pending
//                  (idle drain, so writes below the low watermark aren't stranded)
//   write -> read: at least minBatch writes drained since entering (or none left), and
//                  pendingWrites <= lowWatermark, and reads are pending; or no writes left
//   The exit rule applies to every entry reason, so a read arriving during an idle drain
//   doesn't end it before minBatch writes have issued.
class SplitXactionScheduler(
  nastiParams: NastiParameters,
  depth:       Int,
  cfg:         BaseConfig,
  writeWindow: Int,
)(implicit p:  Parameters
) extends Module {
  // Writes are acknowledged on enqueue, so data-resident writes are bounded by the write
  // queue plus the write window (plus the one W the model accepts ahead of its AW), not by
  // maxWrites. The counters must not saturate below that or pending writes get lost.
  val maxPending = depth + writeWindow + 1
  val io         = IO(new SplitXactionSchedulerIO(nastiParams, cfg, depth, maxPending))

  val readQueue      = Module(new Queue(new XactionSchedulerEntry(nastiParams), depth))
  val writeQueue     = Module(new Queue(new XactionSchedulerEntry(nastiParams), depth))
  val writeRespQueue = Module(new Queue(new XactionSchedulerEntry(nastiParams), depth))

  // AR and AW enqueue independently. A shared arbiter would let a full read queue (reads
  // held back in write mode) block AWs, and with them the writes the drain is waiting for.
  readQueue.io.enq.valid        := io.req.ar.valid
  io.req.ar.ready               := readQueue.io.enq.ready
  readQueue.io.enq.bits.xaction := TransactionMetaData(nastiParams, io.req.ar.bits)
  readQueue.io.enq.bits.addr    := io.req.ar.bits.addr

  val enqWrite = DecoupledHelper(
    io.req.aw.valid,
    writeQueue.io.enq.ready,
    writeRespQueue.io.enq.ready,
  )
  val awEntry  = Wire(new XactionSchedulerEntry(nastiParams))
  awEntry.xaction := TransactionMetaData(nastiParams, io.req.aw.bits)
  awEntry.addr    := io.req.aw.bits.addr

  io.req.aw.ready             := enqWrite.fire(io.req.aw.valid)
  writeQueue.io.enq.bits      := awEntry
  writeQueue.io.enq.valid     := enqWrite.fire(writeQueue.io.enq.ready)
  writeRespQueue.io.enq.bits  := awEntry
  writeRespQueue.io.enq.valid := enqWrite.fire(writeRespQueue.io.enq.ready)

  // Accept up to one additional write data request
  // TODO: More sensible model; maybe track a write buffer volume
  io.req.w.ready := io.pendingWReq <= io.pendingAWReq

  val wLast = io.req.w.fire && io.req.w.bits.last

  // Writes with data resident that have not entered the write window: a write may only
  // enter the window once its data is here
  val unadmittedWrites = SatUpDownCounter(maxPending)
  unadmittedWrites.inc := wLast
  unadmittedWrites.dec := io.nextWrite.fire

  // Writes in the write window whose CASW has not issued
  val windowWrites = SatUpDownCounter(maxPending)
  windowWrites.inc := io.nextWrite.fire
  windowWrites.dec := io.writeIssued

  // What the watermarks judge: writes that can be scheduled, i.e. in the window, or in the
  // write queue with their data here. A write whose data arrived ahead of its AW is not
  // counted, so write mode can't wait on a write that has no address yet.
  val queuedReady   = Mux(unadmittedWrites.value < writeQueue.io.count, unadmittedWrites.value, writeQueue.io.count)
  val pendingWrites = windowWrites.value +& queuedReady
  io.pendingWrites := pendingWrites

  val ackedWrites = SatUpDownCounter(maxPending)
  ackedWrites.inc := wLast
  ackedWrites.dec := writeRespQueue.io.deq.fire

  // --- windows are fed independently
  io.nextRead <> readQueue.io.deq

  val writeAdmit = DecoupledHelper(
    writeQueue.io.deq.valid,
    io.nextWrite.ready,
    !unadmittedWrites.empty,
  )
  io.nextWrite.bits       := writeQueue.io.deq.bits
  io.nextWrite.valid      := writeAdmit.fire(io.nextWrite.ready)
  writeQueue.io.deq.ready := writeAdmit.fire(writeQueue.io.deq.valid)

  // --- read/write mode
  val s = io.drainSettings
  val readsPending = io.readsInWindow || readQueue.io.deq.valid
  val pending      = pendingWrites

  val writeMode  = RegInit(false.B)
  val batchCount = RegInit(0.U(log2Ceil(maxPending + 1).W))
  io.writeMode  := writeMode

  val enterHigh = pending >= s.highWatermark
  val enterOpp  = pending >= s.lowWatermark && pending =/= 0.U && !readsPending

  // Consecutive read-mode cycles with no reads pending (saturates at the counter max)
  val idleCount = RegInit(0.U(WriteDrainSettings.idleCyclesWidth.W))
  when(writeMode || readsPending) {
    idleCount := 0.U
  }.elsewhen(!idleCount.andR) {
    idleCount := idleCount + 1.U
  }
  val enterIdle = s.idleCycles =/= 0.U && idleCount >= s.idleCycles && pending =/= 0.U && !readsPending

  val batchDone = batchCount >= s.minBatch
  val exitDrain = pending === 0.U || (batchDone && pending <= s.lowWatermark && readsPending)

  when(!writeMode) {
    when(enterHigh || enterOpp || enterIdle) {
      writeMode  := true.B
      batchCount := 0.U
    }
  }.otherwise {
    when(io.writeIssued && !batchDone) { batchCount := batchCount + 1.U }
    when(exitDrain) { writeMode := false.B }
  }

  // --- instrumentation
  val stats = RegInit(0.U.asTypeOf(new WriteDrainStats))
  when(!writeMode && enterHigh) { stats.drainsHigh := stats.drainsHigh + 1.U }
  when(!writeMode && !enterHigh && enterOpp) { stats.drainsOpportunistic := stats.drainsOpportunistic + 1.U }
  when(!writeMode && !enterHigh && !enterOpp && enterIdle) { stats.drainsIdle := stats.drainsIdle + 1.U }
  when(io.writeIssued) { stats.writesDrained := stats.writesDrained + 1.U }
  when(writeMode) { stats.writeModeCycles := stats.writeModeCycles + 1.U }
  when(writeMode && readsPending) { stats.readsBlockedCycles := stats.readsBlockedCycles + 1.U }
  io.drainStats := stats

  io.writeSkip.bits           := writeRespQueue.io.deq.bits
  io.writeSkip.valid          := writeRespQueue.io.deq.valid && (ackedWrites.value > 0.U)
  writeRespQueue.io.deq.ready := io.writeSkip.ready && (ackedWrites.value > 0.U)
}

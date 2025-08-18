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

class SpliXactionSchedulerIO(cfg: BaseConfig)(implicit p: Parameters) extends XactionSchedulerIO(cfg)(p){
  //val readForwardXaction = Decoupled(new XactionSchedulerEntry)
  val writeSkip = Decoupled(new XactionSchedulerEntry)
  val doesSchedHavePendingWrites = Input(Bool())
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

class AddressLookupEntry(implicit p: Parameters) extends Bundle {
  val address = UInt(p(NastiKey).addrBits.W)
  val valid = Bool()
}

class QueueAddressLookup(depth: Int)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val newAddress = Flipped(Decoupled(UInt(p(NastiKey).addrBits.W)))
    val addressLookUp = Flipped(Decoupled(UInt(p(NastiKey).addrBits.W)))
    val addressKill = Flipped(Decoupled(UInt(p(NastiKey).addrBits.W)))
    val isHit = Valid(Bool())
  })

  val entries = Reg(Vec(depth, new AddressLookupEntry))

  io.newAddress.ready := true.B
  io.addressLookUp.ready := true.B
  io.addressKill.ready := true.B

  when ( io.newAddress.fire ) {
    val freeEntries = entries.map(!_.valid)
    val freeIndex = PriorityEncoder(freeEntries)
    entries(freeIndex).valid := true.B
    entries(freeIndex).address := io.newAddress.bits
  }

  io.isHit.bits := false.B
  io.isHit.valid := false.B
  when ( io.addressLookUp.fire ) {
    val addressMatches = entries.map( e => { (e.address === io.addressLookUp.bits) && e.valid } )
    io.isHit.bits := addressMatches.reduce(_||_)
    io.isHit.valid := true.B
  }

  when ( io.addressKill.fire ) {
    val addressMatches = entries.map( e => { (e.address === io.addressKill.bits) && e.valid } )
    when ( addressMatches.reduce(_||_) ) {
      val killIndex = PriorityEncoder(addressMatches)
      entries(killIndex).valid := false.B
    }
  }

}

class SplitXactionScheduler(depth: Int, cfg: BaseConfig)(implicit p: Parameters) extends Module {
  val io = IO(new SpliXactionSchedulerIO(cfg))

  import DRAMMasEnums._

  val readQueue = Module(new Queue(new XactionSchedulerEntry, depth))
  val writeQueue = Module(new Queue(new XactionSchedulerEntry, depth))
  val writeRespQueue = Module(new Queue(new XactionSchedulerEntry, 8))
  val writeAddressTable = Module(new QueueAddressLookup(depth))
  val transactionQueueArb = Module(new RRArbiter(new XactionSchedulerEntry, 2))

  val shouldDrainWrites = RegInit(false.B)

  transactionQueueArb.io.in(0).valid := io.req.ar.valid
  io.req.ar.ready := transactionQueueArb.io.in(0).ready
  transactionQueueArb.io.in(0).bits.xaction := TransactionMetaData(io.req.ar.bits)
  transactionQueueArb.io.in(0).bits.addr := io.req.ar.bits.addr

  transactionQueueArb.io.in(1).valid := io.req.aw.valid
  io.req.aw.ready := transactionQueueArb.io.in(1).ready
  transactionQueueArb.io.in(1).bits.xaction := TransactionMetaData(io.req.aw.bits)
  transactionQueueArb.io.in(1).bits.addr := io.req.aw.bits.addr

  writeRespQueue.io.enq.bits.xaction := TransactionMetaData(io.req.aw.bits)
  writeRespQueue.io.enq.bits.addr := io.req.aw.bits.addr
  writeRespQueue.io.enq.valid := io.req.aw.valid

  writeAddressTable.io.newAddress.bits := io.req.aw.bits.addr
  writeAddressTable.io.newAddress.valid := writeQueue.io.enq.fire
  writeAddressTable.io.addressKill.bits := writeQueue.io.deq.bits.addr
  writeAddressTable.io.addressKill.valid := writeQueue.io.deq.fire
  writeAddressTable.io.addressLookUp.bits := io.req.ar.bits.addr
  writeAddressTable.io.addressLookUp.valid := io.req.ar.fire

  // io.readForwardXaction.valid := writeAddressTable.io.isHit.bits && writeAddressTable.io.isHit.valid
  // io.readForwardXaction.bits := transactionQueueArb.io.out.bits

  when ( writeAddressTable.io.isHit.valid ) {
    printf("DRAM: Address lookup for %d\n", io.req.ar.bits.addr)
  }

  // assert(io.readForwardXaction.fire, "DRAM: Firing on read foward path")
  // assert(io.readForwardXaction.valid && !io.readForwardXaction.ready, "DRAM: Read forward path valid, but not ready")
  // when ( io.readForwardXaction.fire ) {
  //   printf("DRAM: Firing on read foward path\n")
  // }

  readQueue.io.enq.bits := transactionQueueArb.io.out.bits
  writeQueue.io.enq.bits := transactionQueueArb.io.out.bits
  transactionQueueArb.io.out.ready := Mux(transactionQueueArb.io.out.bits.xaction.isWrite, writeQueue.io.enq.ready, readQueue.io.enq.ready)
  writeQueue.io.enq.valid := transactionQueueArb.io.out.valid && transactionQueueArb.io.out.bits.xaction.isWrite
  readQueue.io.enq.valid := transactionQueueArb.io.out.valid && !transactionQueueArb.io.out.bits.xaction.isWrite
                             //&& !( writeAddressTable.io.isHit.bits && writeAddressTable.io.isHit.valid )

  when ( readQueue.io.enq.fire ) {
    printf("DRAM: enqueue a read\n")
  }

  when ( readQueue.io.deq.fire ) {
    printf("DRAM: dequeue a read\n")
  }

  when ( writeQueue.io.enq.fire ) {
    printf("DRAM: enqueue a write\n")

    when ( writeAddressTable.io.newAddress.fire ) {
      printf("DRAM: add address to write lookup %d\n", io.req.aw.bits.addr)
    }
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

  val shouldUseHighWatermark = completedWrites.value >= ( depth.U - 8.U )
  val shouldUseLowWatermark = ( completedWrites.value >= 5.U ) && !io.doesSchedHavePendingWrites // this is reads just being lazy
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
  readQueue.io.deq.ready := deqGate.fire(readQueue.io.deq.valid) && !shouldDrainWrites //&& !io.doesSchedHavePendingWrites
  writeQueue.io.deq.ready := writeDeqGate.fire(writeQueue.io.deq.valid)

  when ( io.writeSkip.fire ) {
    printf("DRAM: Write skip fired\n")
  }
  io.writeSkip.bits := writeRespQueue.io.deq.bits
  io.writeSkip.valid := writeRespQueue.io.deq.valid && io.req.w.fire && io.req.w.bits.last
  writeRespQueue.io.deq.ready := io.writeSkip.ready && io.req.w.fire && io.req.w.bits.last
}

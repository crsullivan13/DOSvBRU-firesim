package midas
package models

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import junctions.NastiKey
import midas.widgets._

// schedulerWindowSize: read scheduling window. writeWindowSize: write scheduling window
// (defaults to schedulerWindowSize). Both are also runtime-programmable down from these maxima.
case class FirstReadyFCFSConfig(
  dramKey:                     DramOrganizationParams,
  schedulerWindowSize:         Int,
  transactionQueueDepth:       Int,
  backendKey:                  DRAMBackendKey = DRAMBackendKey(4, 4, DRAMMasEnums.backendLatencyBits),
  params:                      BaseParams,
  writeWindowSize:             Option[Int] = None,
) extends DRAMBaseConfig {
  require(transactionQueueDepth > 0, "FASED DRAM transaction queue depth must be non-zero")

  val readWindow:  Int = schedulerWindowSize
  val writeWindow: Int = writeWindowSize.getOrElse(schedulerWindowSize)
  require(readWindow > 0 && writeWindow > 0, "FASED scheduling windows must be non-zero")

  // Default write-drain settings, in units of pending writes (runtime-programmable)
  val writeHighWatermark: Int = (transactionQueueDepth * 0.8).ceil.toInt
  val writeLowWatermark:  Int = (transactionQueueDepth * 0.2).ceil.toInt
  val writeMinBatch:      Int = 8
  // Read-idle cycles before draining writes below the low watermark (0 = never)
  val writeIdleDrainCycles: Int = 64

  // Upper bounds of the pendingWrites histogram bins (one more bin catches the rest)
  val writeQueueBins: Seq[Int] = Seq(0, 4, 7, 16, 25)

  def elaborate()(implicit p: Parameters): FirstReadyFCFSModel = Module(new FirstReadyFCFSModel(this))
}

class FirstReadyFCFSMMRegIO(val cfg: FirstReadyFCFSConfig) extends BaseDRAMMMRegIO(cfg) {
  // Read window depth (name kept so existing runtime confs still apply)
  val schedulerWindowSize   = Input(UInt(log2Ceil(cfg.readWindow + 1).W))
  val writeWindowSize       = Input(UInt(log2Ceil(cfg.writeWindow + 1).W))
  val transactionQueueDepth = Input(UInt(log2Ceil(cfg.transactionQueueDepth).W))

  val writeHighWatermark = Input(UInt(log2Ceil(cfg.transactionQueueDepth + 1).W))
  val writeLowWatermark  = Input(UInt(log2Ceil(cfg.transactionQueueDepth + 1).W))
  val writeMinBatch      = Input(UInt(log2Ceil(cfg.transactionQueueDepth + 1).W))
  val writeIdleDrainCycles = Input(UInt(WriteDrainSettings.idleCyclesWidth.W))

  // Write-drain instrumentation
  val writeDrain = Output(new WriteDrainStats)
  // Cycles with pendingWrites (data resident, CASW not issued) in each bin of
  // cfg.writeQueueBins: 0, 1-4, 5-7, 8-16, 17-25, >= 26
  val pendingWritesHistogram = Output(Vec(cfg.writeQueueBins.size + 1, UInt(32.W)))

  val registers = dramBaseRegisters ++ Seq(
    (schedulerWindowSize  -> RuntimeSetting(
      default = cfg.readWindow,
      query   = "Read scheduling window depth",
      min     = 1,
      max     = Some(cfg.readWindow),
    )),
    (writeWindowSize      -> RuntimeSetting(
      default = cfg.writeWindow,
      query   = "Write scheduling window depth",
      min     = 1,
      max     = Some(cfg.writeWindow),
    )),
    transactionQueueDepth -> RuntimeSetting(
      default = cfg.transactionQueueDepth,
      query   = "Transaction queue depth",
      min     = 1,
      max     = Some(cfg.transactionQueueDepth),
    ),
    (writeHighWatermark   -> RuntimeSetting(
      default = cfg.writeHighWatermark,
      query   = "Pending writes that force write mode",
      min     = 1,
      max     = Some(cfg.transactionQueueDepth),
    )),
    (writeLowWatermark    -> RuntimeSetting(
      default = cfg.writeLowWatermark,
      query   = "Pending writes for an opportunistic drain, and the drain floor",
      min     = 0,
      max     = Some(cfg.transactionQueueDepth),
    )),
    (writeMinBatch        -> RuntimeSetting(
      default = cfg.writeMinBatch,
      query   = "Minimum writes issued per write-mode episode",
      min     = 0,
      max     = Some(cfg.transactionQueueDepth),
    )),
    (writeIdleDrainCycles -> RuntimeSetting(
      default = cfg.writeIdleDrainCycles,
      query   = "Cycles with no reads pending before draining writes below the low watermark (0 = never)",
      min     = 0,
      max     = Some((1 << WriteDrainSettings.idleCyclesWidth) - 1),
    )),
  )

  def requestSettings(): Unit = {
    Console.println("Configuring First-Ready First-Come First Serve Model")
    setBaseDRAMSettings()
  }
}

class FirstReadyFCFSIO(val cfg: FirstReadyFCFSConfig)(implicit p: Parameters) extends TimingModelIO()(p) {
  val mmReg = new FirstReadyFCFSMMRegIO(cfg)
}

class FirstReadyFCFSModel(cfg: FirstReadyFCFSConfig)(implicit p: Parameters)
    extends TimingModel(cfg)(p)
    with HasDRAMMASConstants {

  val longName = "First-Ready FCFS MAS"
  def printTimingModelGenerationConfig: Unit = {}

  /** ************************** CHISEL BEGINS ********************************
    */

  import DRAMMasEnums._
  lazy val io = IO(new FirstReadyFCFSIO(cfg))

  val timings = io.mmReg.dramTimings

  val backend          = Module(new DRAMBackend(p(NastiKey), cfg.backendKey))
  val xactionScheduler = Module(new SplitXactionScheduler(p(NastiKey), cfg.transactionQueueDepth, cfg, cfg.writeWindow))
  xactionScheduler.io.req          <> nastiReq
  xactionScheduler.io.pendingAWReq := pendingAWReq.value
  xactionScheduler.io.pendingWReq  := pendingWReq.value

  xactionScheduler.io.drainSettings.highWatermark := io.mmReg.writeHighWatermark
  xactionScheduler.io.drainSettings.lowWatermark  := io.mmReg.writeLowWatermark
  xactionScheduler.io.drainSettings.minBatch      := io.mmReg.writeMinBatch
  xactionScheduler.io.drainSettings.idleCycles    := io.mmReg.writeIdleDrainCycles
  io.mmReg.writeDrain                             := xactionScheduler.io.drainStats

  // true: only the write window may issue commands; false: only the read window
  val writeMode = xactionScheduler.io.writeMode

  // Trackers for controller-level structural hazards
  val cmdBusBusy = Module(new DownCounter((maxDRAMTimingBits)))
  cmdBusBusy.io.decr := true.B

  // Forward declared wires
  val selectedCmd = WireInit(cmd_nop)
  val memReqDone  = (selectedCmd === cmd_casr || selectedCmd === cmd_casw)

  // Trackers for DRAM timing violations
  val rankStateTrackers = Seq.fill(cfg.dramKey.maxRanks)(Module(new RankStateTracker(cfg.dramKey)))

  // Window entries for transactions leaving the scheduler. isReady is set further down,
  // once this cycle's command is known.
  def newRef(x: DecoupledIO[XactionSchedulerEntry]): DecoupledIO[FirstReadyFCFSEntry] = {
    val r = Wire(Decoupled(new FirstReadyFCFSEntry(p(NastiKey), cfg)))
    r.valid := x.valid
    r.bits.decode(x.bits, io.mmReg)
    r.bits.mayPRE := false.B // unused: PRE eligibility is computed each cycle (modeReadyBanks)
    x.ready := r.ready
    r
  }
  val newRead  = newRef(xactionScheduler.io.nextRead)
  val newWrite = newRef(xactionScheduler.io.nextWrite)

  // Separate read and write scheduling windows
  val readBuffer  = CollapsingBuffer(
    enq               = newRead,
    depth             = cfg.readWindow,
    programmableDepth = Some(io.mmReg.schedulerWindowSize),
  )
  val writeBuffer = CollapsingBuffer(
    enq               = newWrite,
    depth             = cfg.writeWindow,
    programmableDepth = Some(io.mmReg.writeWindowSize),
  )
  // Reads first, so within the active window the arbiters still pick the oldest
  val refList    = readBuffer.io.entries ++ writeBuffer.io.entries
  val refUpdates = readBuffer.io.updates ++ writeBuffer.io.updates

  // An entry may have a command issued for it (CAS, ACT or PRE) only in its own mode
  def inMode(e: ValidIO[FirstReadyFCFSEntry]): Bool = e.valid && e.bits.xaction.isWrite === writeMode

  xactionScheduler.io.readsInWindow := readBuffer.io.entries.map(_.valid).reduce(_ || _)
  xactionScheduler.io.writeIssued   := selectedCmd === cmd_casw

  // Selects the oldest candidate from all ready references that can legally request a CAS
  val columnArbiter = Module(new Arbiter(refList.head.bits.cloneType, refList.size))

  def checkRankBankLegality(
    getBankField: CommandLegalBools => Bool,
    getRankField: (RankStateTrackerO, UInt) => Bool,
  )(masEntry:     FirstReadyFCFSEntry
  ): Bool = {
    val bankFields = rankStateTrackers.map { rank => VecInit(rank.io.rank.banks.map(getBankField)).asUInt }
    val bankLegal  = (Mux1H(masEntry.rankAddrOH, bankFields) & masEntry.bankAddrOH).orR
    val bankGroup  = cfg.dramKey.bankGroup(masEntry.bankAddr)
    val rankFields = VecInit(rankStateTrackers.map { rank => getRankField(rank.io.rank, bankGroup) }).asUInt
    val rankLegal  = (masEntry.rankAddrOH & rankFields).orR
    rankLegal && bankLegal
  }

  def rankWantsRef(rankAddrOH: UInt): Bool =
    (rankAddrOH & (VecInit(rankStateTrackers.map { _.io.rank.wantREF }).asUInt)).orR

  val canLegallyCASR = checkRankBankLegality(_.canCASR, (rank, bg) => rank.canCASR(bg)) _
  val canLegallyCASW = checkRankBankLegality(_.canCASW, (rank, bg) => rank.canCASW(bg)) _
  val canLegallyACT  = checkRankBankLegality(_.canACT, (rank, bg) => rank.canACT(bg)) _
  val canLegallyPRE  = checkRankBankLegality(_.canPRE, (rank, _) => rank.canPRE) _

  columnArbiter.io.in <> refList.map({ entry =>
    val candidate = V2D(entry)
    val canCASR   = canLegallyCASR(entry.bits) && backend.io.newRead.ready
    val canCASW   = canLegallyCASW(entry.bits)
    candidate.valid := inMode(entry) && entry.bits.isReady &&
      Mux(entry.bits.xaction.isWrite, canCASW, canCASR) &&
      !rankWantsRef(entry.bits.rankAddrOH)

    candidate
  })

  // Banks (per rank) holding a ready entry of the current mode: their open row is still
  // wanted, so no entry may precharge them. Entries of the other mode are ignored, so a
  // waiting write cannot pin a row against a read that needs another one (and vice versa).
  val modeReadyBanks = rankStateTrackers.indices.map { r =>
    refList
      .map { e => Mux(inMode(e) && e.bits.isReady && e.bits.rankAddrOH(r), e.bits.bankAddrOH, 0.U) }
      .reduce(_ | _)
  }
  def mayPRE(e: FirstReadyFCFSEntry): Bool = !(Mux1H(e.rankAddrOH, modeReadyBanks) & e.bankAddrOH).orR

  val entryWantsPRE = refList.map { ref =>
    inMode(ref) && !ref.bits.isReady && mayPRE(ref.bits) && canLegallyPRE(ref.bits)
  }
  val entryWantsACT = refList.map { ref =>
    inMode(ref) && ref.bits.wantACT() && canLegallyACT(ref.bits) &&
    !rankWantsRef(ref.bits.rankAddrOH)
  }

  val preBank    = PriorityMux(entryWantsPRE, refList.map(_.bits.bankAddr))
  val preRank    = PriorityMux(entryWantsPRE, refList.map(_.bits.rankAddr))
  val suggestPre = entryWantsPRE.reduce { _ || _ }

  val actRank = PriorityMux(entryWantsACT, refList.map(_.bits.rankAddr))
  val actBank = PriorityMux(entryWantsACT, refList.map(_.bits.bankAddr))
  val actRow  = PriorityMux(entryWantsACT, refList.map(_.bits.rowAddr))

  // See if the oldest pending row reference wants a PRE an ACT
  val suggestAct = (entryWantsACT
    .zip(entryWantsPRE))
    .foldRight(false.B)({ case ((act, pre), current) =>
      Mux(act, true.B, !pre && current)
    })

  // NB: These are not driven for all command types. Ex. When issuing a CAS cmdRow
  // will not correspond to the row of the CAS command since that is implicit
  // to the state of the bank.
  val cmdBank   = WireInit(UInt(cfg.dramKey.bankBits.W), init = preBank)
  val cmdBankOH = UIntToOH(cmdBank)
  val cmdRank   = WireInit(UInt(cfg.dramKey.rankBits.W), init = columnArbiter.io.out.bits.rankAddr)
  val cmdRow    = actRow

  val refreshUnit = Module(new RefreshUnit(cfg.dramKey)).io
  refreshUnit.ranksInUse := io.mmReg.rankAddr.maskToOH()
  refreshUnit.rankStati.zip(rankStateTrackers).foreach { case (refInput, tracker) =>
    refInput := tracker.io.rank
  }

  when(refreshUnit.suggestREF) {
    selectedCmd := cmd_ref
    cmdRank     := refreshUnit.refRankAddr
  }.elsewhen(refreshUnit.suggestPRE) {
    selectedCmd := cmd_pre
    cmdRank     := refreshUnit.preRankAddr
    cmdBank     := refreshUnit.preBankAddr
  }.elsewhen(columnArbiter.io.out.valid) {
    selectedCmd := Mux(columnArbiter.io.out.bits.xaction.isWrite, cmd_casw, cmd_casr)
    cmdBank     := columnArbiter.io.out.bits.bankAddr
  }.elsewhen(suggestAct) {
    selectedCmd := cmd_act
    cmdRank     := actRank
    cmdBank     := actBank
  }.elsewhen(suggestPre) {
    selectedCmd := cmd_pre
    cmdRank     := preRank
  }

  // Remove a reference if it is granted a column access
  columnArbiter.io.out.ready := selectedCmd === cmd_casw || selectedCmd === cmd_casr

  // Take the readies from the arbiter, and kill the selected entry
  // Both windows count here: a ready entry of either mode keeps the row open under
  // the closed-page policy
  val entriesStillReady = refUpdates.zip(columnArbiter.io.in).map { case (ref, sel) =>
    when(sel.fire) { ref.valid := false.B }
    // If the entry is not killed, but shares the same open row as the killed reference, return true
    !sel.fire && ref.valid && ref.bits.isReady &&
    cmdBank === ref.bits.bankAddr && cmdRank === ref.bits.rankAddr
  }

  val otherReadyEntries = entriesStillReady.reduce { _ || _ }
  val casAutoPRE        = Mux(io.mmReg.openPagePolicy, false.B, memReqDone && !otherReadyEntries)

  // Mark entries that now hit in an open row buffer, or not after a precharge
  refUpdates.foreach({ ref =>
    when(cmdRank === ref.bits.rankAddr && cmdBank === ref.bits.bankAddr) {
      when(selectedCmd === cmd_act) {
        ref.bits.isReady := ref.bits.rowAddr === cmdRow
      }.elsewhen(selectedCmd === cmd_pre) {
        ref.bits.isReady := false.B
      }
    }
  })

  // New references: 1) row just opened or 2) already open && no precharge to that bank this cycle
  Seq(newRead, newWrite).foreach { n =>
    val rowHits       = VecInit(rankStateTrackers.map { tracker =>
      VecInit(tracker.io.rank.banks.map { _.isRowHit(n.bits) }).asUInt
    })
    val addrMatch     = n.bits.addrMatch(cmdRank, cmdBank, Some(cmdRow))
    val bankAddrMatch = n.bits.addrMatch(cmdRank, cmdBank)
    n.bits.isReady := selectedCmd === cmd_act && addrMatch ||
      (rowHits(n.bits.rankAddr) & n.bits.bankAddrOH).orR &&
      !(memReqDone && casAutoPRE && bankAddrMatch) && !(selectedCmd === cmd_pre && bankAddrMatch)
  }

  // Pending-write occupancy histogram (see FirstReadyFCFSMMRegIO.writeQueueBins)
  val pendingWrites = xactionScheduler.io.pendingWrites
  val pwBins        = cfg.writeQueueBins
  val pwHist        = Seq.fill(pwBins.size + 1)(RegInit(0.U(32.W)))
  val pwInBin       = pwBins.zipWithIndex.map { case (m, i) =>
    pendingWrites <= m.U && (if (i == 0) true.B else pendingWrites > pwBins(i - 1).U)
  } :+ (pendingWrites > pwBins.last.U)
  pwHist.zip(pwInBin).foreach { case (h, in) => when(in) { h := h + 1.U } }
  io.mmReg.pendingWritesHistogram := VecInit(pwHist)

  rankStateTrackers.zip(UIntToOH(cmdRank).asBools).foreach { case (state, cmdUsesThisRank) =>
    state.io.selectedCmd     := selectedCmd
    state.io.cmdBankOH       := cmdBankOH
    state.io.cmdRow          := cmdRow
    state.io.autoPRE         := casAutoPRE
    state.io.cmdUsesThisRank := cmdUsesThisRank

    state.io.timings := timings
    state.io.tCycle  := tCycle
  }

  cmdBusBusy.io.set.bits  := timings.tCMD - 1.U
  cmdBusBusy.io.set.valid := selectedCmd =/= cmd_nop

  // Writes are acknowledged as soon as they are accepted into the write queue,
  // rather than when they drain out to the DRAM command bus.
  val newWriteSkip = Wire(Decoupled(new FirstReadyFCFSEntry(p(NastiKey), cfg)))
  newWriteSkip.valid                  := xactionScheduler.io.writeSkip.valid
  xactionScheduler.io.writeSkip.ready := newWriteSkip.ready
  newWriteSkip.bits.decode(xactionScheduler.io.writeSkip.bits, io.mmReg)
  newWriteSkip.bits.isReady           := DontCare
  newWriteSkip.bits.mayPRE            := DontCare

  backend.io.tCycle        := tCycle
  backend.io.newRead.bits  := ReadResponseMetaData(p(NastiKey), columnArbiter.io.out.bits.xaction)
  backend.io.newRead.valid := memReqDone && !columnArbiter.io.out.bits.xaction.isWrite
  backend.io.readLatency   := timings.tCAS + timings.tAL + io.mmReg.backendLatency

  backend.io.newWrite.bits  := WriteResponseMetaData(p(NastiKey), newWriteSkip.bits.xaction)
  backend.io.newWrite.valid := newWriteSkip.valid
  newWriteSkip.ready        := backend.io.newWrite.ready
  backend.io.writeLatency   := 1.U

  wResp <> backend.io.completedWrite
  rResp <> backend.io.completedRead

  // Dump the cmd stream
  val cmdMonitor = Module(new CommandBusMonitor())
  cmdMonitor.io.cmd       := selectedCmd
  cmdMonitor.io.rank      := cmdRank
  cmdMonitor.io.bankGroup := cfg.dramKey.bankGroup(cmdBank)
  cmdMonitor.io.bank      := cfg.dramKey.bankInGroup(cmdBank)
  cmdMonitor.io.row       := cmdRow
  cmdMonitor.io.autoPRE   := casAutoPRE

  val powerStats = (rankStateTrackers).zip(UIntToOH(cmdRank).asBools).map { case (rankState, cmdUsesThisRank) =>
    val powerMonitor = Module(new RankPowerMonitor(cfg.dramKey))
    powerMonitor.io.selectedCmd     := selectedCmd
    powerMonitor.io.cmdUsesThisRank := cmdUsesThisRank
    powerMonitor.io.rankState       := rankState.io.rank
    powerMonitor.io.stats
  }

  io.mmReg.rankPower := VecInit(powerStats)
}

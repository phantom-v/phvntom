package rv64_nstage.tlb

import chisel3._
import chisel3.util._
import rv64_nstage.core.phvntomParams
import utils._
import rv64_nstage.register.SATP
import rv64_nstage.register.CSR
import mem._

// TODO This should be an Arbiter when TLB is setup
// The privilege Mode should flow with the pipeline because
// CSR is in Mem1 but this DMMU might be in later Stage
//   [ITLB]      [DTLB]
//       \        /
//        \      /
//         \    /
//       [MMU & PTW]  [ICACHE]   [DCACHE/UNCACHE]
//            |           |            |
//            |           |            |
//            |           |            |
//        ||||||||   AXI X Bar   ||||||||||
class PTWalkerIO extends Bundle with phvntomParams {
  val keep_val = Input(Bool())
  val valid = Input(Bool())
  val va = Input(UInt(xlen.W))
  val flush_all = Input(Bool()) // TODO flush TLB, do nothing now
  val satp_val = Input(UInt(xlen.W))
  val current_p = Input(UInt(2.W))
  val is_mem = Input(Bool())
  val force_s_mode = Input(Bool())
  val sum = Input(UInt(1.W))
  // Protection
  val is_inst = Input(Bool())
  val is_load = Input(Bool())
  val is_store = Input(Bool())
  // Output
  val stall_req = Output(Bool())
  val pa = Output(UInt(xlen.W))
  val pf = Output(Bool())
  val af = Output(Bool()) // TODO PMA PMP to generate access fault
  // Memory Interface
  val cache_req_ready = Input(Bool())
  val cache_req_valid = Output(Bool())
  val cache_req_addr = Output(UInt(xlen.W))
  val cache_resp_valid = Input(Bool())
  val cache_resp_rdata = Input(UInt((cachiLine * cachiBlock).W))
}

class PTWalker(name: String) extends Module with phvntomParams {
  val io = IO(new PTWalkerIO)

  val s_idle = 0.U(3.W)
  val s_busy = 1.U(3.W)
  val s_receive = 2.U(3.W)
  val s_check = 3.U(3.W)
  val s_finish = 4.U(3.W)

  val satp_mode = io.satp_val(xlen - 1, 60)
  val satp_asid = io.satp_val(59, 44)
  val satp_ppn = io.satp_val(43, 0)

  // To AXI Uncache Wrapper
  val pte_addr = RegInit(UInt(xlen.W), 0.U)
  val valid_access = RegInit(Bool(), false.B)
  val ready_to_receive = io.cache_req_ready

  // From AXI Uncache Wrapper
  val axi_valid = io.cache_resp_valid
  val axi_rdata = MuxLookup(io.cache_req_addr(4, 3), "hdeadbeef".U,
    Seq(
      "b00".U -> io.cache_resp_rdata(63, 0),
      "b01".U -> io.cache_resp_rdata(2 * 64 - 1, 64),
      "b10".U -> io.cache_resp_rdata(3 * 64 - 1, 2 * 64),
      "b11".U -> io.cache_resp_rdata(4 * 64 - 1, 3 * 64)
    )
  )
  val pte_ppn = axi_rdata(53, 10)

  val level = 3.U(2.W)
  val entry_recv = RegInit(UInt(xlen.W), 0.U)
  val next_pte_pa = RegInit(UInt(xlen.W), 0.U)
  val state = RegInit(UInt(s_idle.getWidth.W), s_idle)
  val next_state = Wire(UInt(s_idle.getWidth.W))
  val page_fault = Wire(Bool())
  val lev = RegInit(UInt(2.W), level - 1.U)
  val last_pte = RegInit(UInt(xlen.W), 0.U)
  val final_pa = Cat(Mux(lev === 0.U, last_pte(53, 10), Mux(lev === 1.U, Cat(last_pte(53, 19), io.va(20, 12)), Cat(last_pte(53, 28), io.va(29, 12)))), io.va(11, 0))

  // Some Constants
  // PTE SIZE = 8
  // PAGE SIZE = 2^12
  val l_pte_size = 3.U
  val l_page_size = 12.U

  def illegal_va(va: UInt): Bool = {
    Mux(va(validVABits - 1), !va(xlen - 1, validVABits).andR, va(xlen - 1, validVABits).orR)
  }

  def is_pte_valid(pte: UInt): Bool = {
    !(pte(0) === 0.U || (pte(1) === 0.U && pte(2) === 1.U))
  }

  def is_final_pte(pte: UInt): Bool = {
    pte(1) === 1.U || pte(3) === 1.U
  }

  def pass_protection_check(pte: UInt, is_inst: Bool, is_load: Bool, is_store: Bool): Bool = {
    Mux(is_inst, pte(3), Mux(is_load, pte(1), Mux(is_store, pte(2), true.B)))
  }

  def misaligned_spage(lev: UInt, last_pte: UInt): Bool = {
    Mux(lev === 0.U, false.B, Mux(lev === 1.U, last_pte(18, 10).orR, last_pte(27, 10).orR))
  }

  def pass_pmp_pma(last_pte: UInt): Bool = {
    // TODO Currently this is done out of MMU
    true.B
  }

  def sum_is_zero_fault(sum: UInt, force_s_mode: Bool, current_p: UInt, last_pte: UInt): Bool = {
    Mux(sum === 1.U, false.B, Mux(current_p === CSR.PRV_S || force_s_mode, last_pte(4), false.B))
  }

  // TODO ***CAUTION***
  // TODO If accessing pte violates a PMA or PMP check, raise an access-fault exception corresponding
  // TODO to the original access type.
  // Sequential Logic
  state := next_state
  when(state === s_idle) {
    last_pte := io.va
    when(next_state === s_busy) {
      pte_addr := (satp_ppn << l_page_size) + (io.va(38, 30) << l_pte_size)
      lev := level - 1.U
      valid_access := true.B
    }
  }.elsewhen(state === s_receive) {
    when(next_state === s_idle) {
      valid_access := false.B
    }.elsewhen(next_state === s_check) {
      valid_access := false.B
      last_pte := axi_rdata
    }.elsewhen(axi_valid) {
      pte_addr := (pte_ppn << l_page_size) + (Mux(lev === 2.U, io.va(29, 21), io.va(20, 12)) << l_pte_size)
      lev := lev - 1.U
    }
  }.elsewhen(state === s_check) {
    last_pte := final_pa
  }

  if(pipeTrace) {
    if (name == "dmmu") {
      //    printf("DMMU\tstate %x\t\tpf %x\t\tva %x\t\tpa %x\n", state, page_fault, io.va, io.pa)
      //    printf("\tLevel %x\t\taxi_respv %x\taxi_pte %x\tstall_req %x\n", lev, axi_valid, axi_rdata, io.stall_req)
      //    printf("\tpte_valid %x\tis_final %x\tchk_prt_pass %x\t\t\tnot_misa %x\t\tsum_pass %x\t\t last_pte %x\n", is_pte_valid(axi_rdata),
      //      is_final_pte(axi_rdata), pass_protection_check(last_pte, io.is_inst, io.is_load, io.is_store), !misaligned_spage(lev, last_pte),
      //      !sum_is_zero_fault(io.sum, io.force_s_mode, io.current_p, last_pte), last_pte)
      //    printf("\n")
    } else {
      printf("IMMU\tstate %x\t\tpf %x\t\tva %x\t\tpa %x\tnx_addr %x\n", state, page_fault, io.va, io.pa, io.cache_req_addr)
      printf("\tLevel %x\t\taxi_respv %x\taxi_pte %x\tstall_req %x\t\treq_valid %x\n", lev, axi_valid, axi_rdata, io.stall_req, valid_access)
      printf("\tpte_valid %x\tis_final %x\tchk_prt_pass %x\t\t\tnot_misa %x\t\tsum_pass %x\t\t last_pte %x\n", is_pte_valid(axi_rdata),
        is_final_pte(axi_rdata), pass_protection_check(last_pte, io.is_inst, io.is_load, io.is_store), !misaligned_spage(lev, last_pte),
        !sum_is_zero_fault(io.sum, io.force_s_mode, io.current_p, last_pte), last_pte)
      printf("\tall_line %x\n", io.cache_resp_rdata)
      printf("\n")
    }
  }

  // Combinational Logic
  when(state === s_idle) {
    when(!io.valid || satp_mode === SATP.Bare || (io.current_p === CSR.PRV_M &&
      (!io.is_mem || !io.force_s_mode))) {
      next_state := s_idle
      page_fault := false.B
    }.otherwise {
      when(illegal_va(io.va)) {
        page_fault := true.B
        next_state := s_idle
      }.otherwise {
        page_fault := false.B
        next_state := s_busy
      }
    }
  }.elsewhen(state === s_busy) {
    page_fault := false.B
    when(ready_to_receive) {
      next_state := s_receive
    }.otherwise {
      next_state := s_busy
    }
  }.elsewhen(state === s_receive) {
    when(axi_valid) {
      when(is_pte_valid(axi_rdata)) {
        when(is_final_pte(axi_rdata)) {
          page_fault := false.B
          next_state := s_check
        }.elsewhen(lev === 0.U) {
          page_fault := true.B
          next_state := s_idle
        }.otherwise {
          page_fault := false.B
          next_state := s_busy
        }
      }.otherwise {
        page_fault := true.B
        next_state := s_idle
      }
    }.otherwise {
      page_fault := false.B
      next_state := s_receive
    }
  }.elsewhen(state === s_check) {
    when(pass_protection_check(last_pte, io.is_inst, io.is_load, io.is_store)) {
      when(misaligned_spage(lev, last_pte) || !pass_pmp_pma(last_pte) ||
        sum_is_zero_fault(io.sum, io.force_s_mode, io.current_p, last_pte)) {
        page_fault := true.B
        next_state := s_idle
      }.otherwise {
        page_fault := false.B
        next_state := s_finish
      }
    }.otherwise {
      page_fault := true.B
      next_state := s_idle
    }
  }.otherwise {
    page_fault := false.B
    next_state := s_idle
  }

  io.pf := page_fault
  io.af := false.B
  io.stall_req := next_state =/= s_idle
  io.pa := Mux((state === s_idle && !io.keep_val) || io.pf, io.va, last_pte)
//  io.pa := Mux((state === s_idle && (!io.valid || satp_mode === SATP.Bare || io.current_p === CSR.PRV_M) ||
//    io.pf), io.va, last_pte)

  io.cache_req_valid := valid_access
  io.cache_req_addr := pte_addr
}


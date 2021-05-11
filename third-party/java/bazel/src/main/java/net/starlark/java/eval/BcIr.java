package net.starlark.java.eval;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Intermediate code representation.
 *
 * <p>This is similar to bytecode with these differences:
 *
 * <ul>
 *   <li>Instructions are represented by java objects, not by ints
 *   <li>Strings and objects are stored as is, not indexed
 *   <li>There are special instructions to allocate and release slots
 *   <li>There's an instruction to patch jump address
 *   <li>There's an instruction to close for loop
 *   <li>IR code is address-independent: two IR objects can be concatenated to produce valid IR,
 *       unlike bytecode which contain absolute addresses and absolute indices
 * </ul>
 */
class BcIr {

  /** Marker object to assert certain functions can be constructed only by this class. */
  static class Friend {
    private Friend() {}

    /** Call this function to mute unused parameter warning. */
    void markUsed() {}

    private static final Friend FRIEND = new Friend();
  }

  final ArrayList<BcIrInstr> instructions = new ArrayList<>();

  /** Serialize this bytecode into executable bytecode. */
  void write(BcWriter writer) {
    BcIrWriteContext writeContext = new BcIrWriteContext(writer);
    for (int i = 0; i < instructions.size(); i++) {
      BcIrInstr instr = instructions.get(i);
      try {
        instr.write(writeContext);
      } catch (Exception e) {
        throw new IllegalStateException(
            String.format("failed to write instruction %s of %s", i, this), e);
      }
    }
    try {
      writeContext.assertWrittenCorrectly();
    } catch (Exception e) {
      throw new IllegalStateException(String.format("failed to write %s", this), e);
    }
  }

  /** Add instruction. */
  void add(BcIrInstr instr) {
    instructions.add(instr);
  }

  /** Allocate slot. */
  BcIrSlot.LazyLocal allocSlot(String label) {
    BcIrSlot.LazyLocal lazyLocal = new BcIrSlot.LazyLocal(Friend.FRIEND, label);
    add(new BcIrInstr.AllocSlot(lazyLocal, Friend.FRIEND));
    return lazyLocal;
  }

  /** Copy slot to a fresh local slot if necessary. */
  BcIrSlot.AnyLocal makeLocal(BcWriter.LocOffset locOffset, BcIrSlot slot, String label) {
    if (slot instanceof BcIrSlot.AnyLocal) {
      return (BcIrSlot.AnyLocal) slot;
    } else {
      BcIrSlot.LazyLocal local = allocSlot(label);
      add(new BcIrInstr.Cp(locOffset, slot, local));
      return local;
    }
  }

  /** Add forward jump instruction. */
  BcIrInstr.JumpLabel br(BcWriter.LocOffset locOffset) {
    BcIrInstr.JumpLabel jumpLabel = new BcIrInstr.JumpLabel(Friend.FRIEND);
    add(new BcIrInstr.Br(locOffset, jumpLabel));
    return jumpLabel;
  }

  /** Add conditional forward jump instruction. */
  BcIrInstr.JumpLabel ifBr(
      BcWriter.LocOffset locOffset, BcIrSlot.AnyLocal cond, BcWriter.JumpCond jumpCond) {
    BcIrInstr.JumpLabel jumpLabel = new BcIrInstr.JumpLabel(Friend.FRIEND);
    add(new BcIrInstr.IfBr(locOffset, cond, jumpCond, jumpLabel));
    return jumpLabel;
  }

  /** Add conditional forward jump instructions. */
  BcIrInstr.JumpLabel ifBr(
      BcWriter.LocOffset locOffset, BcIrSlot cond, BcWriter.JumpCond jumpCond) {
    BcIrSlot.AnyLocal condLocal = makeLocal(locOffset, cond, "cond");
    return ifBr(locOffset, condLocal, jumpCond);
  }

  /** Add all instructions from that IR to this IR. */
  void addAll(BcIr that) {
    this.instructions.addAll(that.instructions);
  }

  /** Add jump labels to current position. */
  public void addJumpLabels(List<BcIrInstr.JumpLabel> instrs) {
    this.instructions.addAll(instrs);
  }

  /** IR instruction count. Note bytecode instruction count may differ. */
  int size() {
    return instructions.size();
  }

  /** Get instruction by index, or null if index is out of range. */
  @Nullable
  private BcIrInstr getOrNull(int i) {
    Preconditions.checkArgument(i >= 0);
    return i < instructions.size() ? instructions.get(i) : null;
  }

  /**
   * Get instruction by index, or null if index is out of range or instruction is of different type.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private <I extends BcIrInstr> I getOrNull(int i, Class<I> instrType) {
    BcIrInstr instr = getOrNull(i);
    return instrType.isInstance(instr) ? (I) instr : null;
  }

  /** Instruction count is zero. */
  boolean isEmpty() {
    return size() == 0;
  }

  void assertUnchanged(int size) {
    if (size != this.size()) {
      if (size > this.instructions.size()) {
        throw new IllegalStateException(
            String.format(
                "IR changed, expected size: %s, current size: %s; IR: %s",
                size, this.size(), this.instructions));
      } else {
        throw new IllegalStateException(
            String.format(
                "IR changed, expected size: %s, current size: %s; saved: %s, extra: %s",
                size,
                this.size(),
                this.instructions.subList(0, size),
                this.instructions.subList(size, instructions.size())));
      }
    }
  }

  /** Check if this IR always returns const value. */
  @Nullable
  Object returnsConst() {
    BcIrInstr.Return returnInstr = getOrNull(0, BcIrInstr.Return.class);
    if (returnInstr != null) {
      return returnInstr.value.constValue();
    } else {
      return null;
    }
  }

  /** Check if function returns {@code type(p0) == 'xxx'} of parameter 0. */
  @Nullable
  String returnsTypeIsOfParam0() {
    BcIrInstr.AllocSlot allocSlot = getOrNull(0, BcIrInstr.AllocSlot.class);
    BcIrInstr.TypeIs typeIs = getOrNull(1, BcIrInstr.TypeIs.class);
    BcIrInstr.Return returnInstr = getOrNull(2, BcIrInstr.Return.class);

    if (allocSlot == null || typeIs == null || returnInstr == null) {
      return null;
    }

    if (allocSlot.lazyLocal != typeIs.result || typeIs.result != returnInstr.value) {
      return null;
    }

    if (!(typeIs.expr instanceof BcIrSlot.Local) || ((BcIrSlot.Local) typeIs.expr).index != 0) {
      return null;
    }

    return typeIs.type;
  }

  @Override
  public String toString() {
    return instructions.toString();
  }
}

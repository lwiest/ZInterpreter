/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Lorenz Wiest
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

// LW 13-NOV-2020 Created
// LW 19-DEC-2020 Completed first running version

package de.lorenzwiest.zmachine;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class ZInterpreter {

	////////////////////////////////////////////////////////////////////////////

	private static class ZMachine {

		////////////////////////////////////////////////////////////////////////////

		private class Header {
			private int versionNumber;
			private int flags1;
			private int releaseNumber;
			private int baseHighMemoryAddr;
			private int initialPC;
			private int dictionaryAddr;
			private int objectTableAddr;
			private int globalVariablesTableAddr;
			private int baseStaticMemoryAddr;
			private String serialCode;
			private int abbreviationTableAddr;
			private int lengthOfFile;

			public Header(ZMachine zm) {
				this.versionNumber = zm.getByte(0x00);
				this.flags1 = zm.getByte(0x01);
				this.releaseNumber = zm.getWord(0x02);
				this.baseHighMemoryAddr = zm.getWord(0x04);
				this.initialPC = zm.getWord(0x06);
				this.dictionaryAddr = zm.getWord(0x08);
				this.objectTableAddr = zm.getWord(0x0A);
				this.globalVariablesTableAddr = zm.getWord(0x0C);
				this.baseStaticMemoryAddr = zm.getWord(0x0E);

				byte[] bytesSerialCode = new byte[6];
				System.arraycopy(zm.story, 0x12, bytesSerialCode, 0, 6);
				this.serialCode = new String(bytesSerialCode);

				this.abbreviationTableAddr = zm.getWord(0x18);
				this.lengthOfFile = zm.getWord(0x1A) * 2;
			}
		}

		////////////////////////////////////////////////////////////////////////////

		private class Stack {

			// Sample stack
			//
			// Index Comment         Value
			//
			//  1023                 [ * ]
			//  .... ............... .....
			//    17 stack_2         [ * ] <-- this.topIndex
			//    16 stack_1         [ * ]
			//    15 local_1         [ * ]
			//    14 num locals      [ 1 ]
			//    13 prev stackFrame [ 4 ] <-- this.stackFrameIndex
			//    12 returnAddr_Lo   [ * ]
			//    11 returnAddr_Hi   [ * ]
			//    10 stack_3         [ * ]
			//     9 stack_2         [ * ]
			//     8 stack_1         [ * ]
			//     7 local_2         [ * ]
			//     6 local_1         [ * ]
			//     5 num locals      [ 2 ]
			//     4 prev stackFrame [-1 ]
			//     3 returnAddr_Lo   [ * ]
			//     2 returnAddr_Hi   [ * ]
			//     1 stack_2         [ * ]
			//     0 stack_1         [ * ]
			//  ---------------------------
			//    -1

			private final static int STACK_SIZE = 1024;

			private int[] stack;
			private int topIndex;
			private int stackFrameIndex; // points to previous stack frame

			public Stack() {
				this.stack = new int[STACK_SIZE];
				reset();
			}

			public void reset() {
				this.topIndex = -1;
				this.stackFrameIndex = -1;
			}

			public void push(int value) {
				if (this.topIndex >= STACK_SIZE - 1) {
					halt("push() - Stack overflow");
				}
				this.topIndex++;
				this.stack[this.topIndex] = value;
			}

			public void pushInt32(int value) {
				int hi = (value >> 16) & 0xFFFF;
				int lo = value & 0xFFFF;

				push(hi);
				push(lo);
			}

			public int pop() {
				if (this.topIndex < 0) {
					halt("pop() - Stack underflow");
				}

				int value = this.stack[this.topIndex];
				this.topIndex--;
				return value;
			}

			public int popInt32() {
				int lo = pop();
				int hi = pop();
				return (hi << 16) | lo;
			}

			public int peek(int index) {
				if ((index < 0) || (index > this.topIndex)) {
					halt(String.format("peek() - Index %d out of bounds [%d..%d]", index, 0, this.topIndex));
				}
				return this.stack[index];
			}

			public void poke(int index, int value) {
				if ((index < 0) || (index > this.topIndex)) {
					halt(String.format("poke() - Index %d out of bounds [%d..%d]", index, 0, this.topIndex));
				}
				this.stack[index] = value;
			}
		}

		////////////////////////////////////////////////////////////////////////////

		private final static String EOL = "\n"; // platform-independent EOL

		private final static int OPERAND_LARGE = 0b00;
		private final static int OPERAND_SMALL = 0b01;
		private final static int OPERAND_VARIABLE = 0b10;
		private final static int OPERAND_OMITTED = 0b11;

		private final static int WORD_SIZE = 2;

		private byte[] story;
		private Header header;
		private Stack stack;
		private int pc; // a 32-bit value
		private boolean isRunning;

		public ZMachine(byte[] story) {
			this.story = story;
			this.header = new Header(this);
			this.pc = this.header.initialPC;
			this.stack = new Stack();
			this.isRunning = true;
		}

		public int getByte(int index) {
			return this.story[index] & 0xFF;
		}

		public void setByte(int index, int value) {
			this.story[index] = (byte) (value & 0xFF);
		}

		public int getWord(int index) {
			int hi = getByte(index);
			int lo = getByte(index + 1);
			return (hi << 8) | lo;
		}

		public void setWord(int index, int value) {
			int hi = value >> 8;
			int lo = value;

			setByte(index, hi);
			setByte(index + 1, lo);
		}

		// utilities

		public boolean isBitSet(int value, int bitPos) {
			return (value & (1 << bitPos)) != 0;
		}

		public boolean isBitClear(int value, int bitPos) {
			return isBitSet(value, bitPos) == false;
		}

		public int toInt32(int value) {
			if (isBitSet(value, 15)) {
				value |= 0xFFFF0000;
			}
			return value;
		}

		public int toUint16(int value) {
			return value & 0xFFFF;
		}

		// consume bytes, words, ...

		public int peekByte() {
			return getByte(this.pc);
		}

		public int consumeByte() {
			int result = getByte(this.pc);
			this.pc++;
			return result;
		}

		public int consumeWord() {
			int hi = consumeByte();
			int lo = consumeByte();
			return (hi << 8) | lo;
		}

		public String consumeString() {
			String str = decodeZString(this.pc);
			do {
				int word = consumeWord();
				if (isBitSet(word, 15)) {
					break;
				}
			} while (true);
			return str;
		}

		public int consumeOperand(int opType) {
			int result = -1;
			switch (opType) {
				case OPERAND_SMALL:
					result = consumeByte(); // do we expect signed values here?
					break;
				case OPERAND_LARGE:
					result = consumeWord();
					break;
				case OPERAND_VARIABLE:
					int varNumber = consumeByte();
					result = getVariableValue(varNumber);
					break;
				default:
					halt(String.format("consumeOperand() - Invalid operand type 0x%x", opType));
					break;
			}
			return result;
		}

		public int[] consumeOperands(int opTypes) {
			int[] aTmp = new int[4];
			int cnt = 0;

			for (int i = 6; i >= 0; i -= 2) {
				int opType = (opTypes >> i) & 0b11;
				if (opType == ZMachine.OPERAND_OMITTED) {
					break;
				}
				int opValue = consumeOperand(opType);
				aTmp[cnt] = opValue;
				cnt++;
			}

			int[] result = new int[cnt];
			System.arraycopy(aTmp, 0, result, 0, cnt);
			return result;
		}

		public void consumeAndStore(int value) {
			int varNumber = consumeByte();
			setVariableValue(varNumber, value);
		}

		public void consumeAndBranch(boolean isBranch) {
			int branchByte1 = consumeByte();
			boolean isBranchOnTrue = isBitSet(branchByte1, 7);
			boolean hasBranchByte2 = isBitClear(branchByte1, 6);
			int offset = branchByte1 & 0b11_1111;
			if (hasBranchByte2) {
				int branchByte2 = consumeByte();
				offset = (offset << 8) | branchByte2;
				if (isBitSet(offset, 13)) {
					offset |= 0xC000; // sign extension
				}
			}
			if (isBranch == isBranchOnTrue) {
				if (offset == 0) {
					zmreturn(0);
				} else if (offset == 1) {
					zmreturn(1);
				} else {
					this.pc = (this.pc + toInt32(offset)) - 2;
				}
			}
		}

		// get/set variable values

		public int getVariableValue(int varNumber) {
			int value = -1;
			if (varNumber == 0) {
				value = this.stack.pop();
			} else if ((varNumber >= 1) && (varNumber <= 15)) {
				value = getLocalVariableValue(varNumber);
			} else if ((varNumber >= 16) && (varNumber <= 255)) {
				value = getGlobalVariableValue(varNumber);
			} else {
				halt(String.format("getVariableValue() - Variable number %d out of bounds [%d..%d]", varNumber, 1, 255));
			}
			return value;
		}

		private int getLocalVariableIndex(int localVarNumber /* 1..15 */) {
			int numLocalVars = this.stack.peek(this.stack.stackFrameIndex + 1);
			if ((localVarNumber < 1) || (localVarNumber > numLocalVars)) {
				halt(String.format("getLocalVariableAddress() - Local variable number %d out of bounds [%d..%d]", localVarNumber, 1, numLocalVars));
			}
			int localIndex = this.stack.stackFrameIndex + 1 + localVarNumber;
			return localIndex;
		}

		private int getGlobalVariableAddress(int globalVarNumber /* 16..255 */) {
			if ((globalVarNumber < 16) || (globalVarNumber > 255)) {
				halt(String.format("getGlobalVariableAddress() - Global variable number %d out of bounds [%d..%d]", globalVarNumber, 16, 255));
			}
			int globalAddr = this.header.globalVariablesTableAddr + ((globalVarNumber - 16) * WORD_SIZE);
			return globalAddr;
		}

		private int getLocalVariableValue(int localVarNumber /* 1..15 */) {
			int localIndex = getLocalVariableIndex(localVarNumber);
			int value = this.stack.peek(localIndex);
			return value;
		}

		private int getGlobalVariableValue(int globalVarNumber /* 16..255 */) {
			int globalAddr = getGlobalVariableAddress(globalVarNumber);
			int value = getWord(globalAddr);
			return value;
		}

		public void setVariableValue(int varNumber, int value) {
			if (varNumber == 0) {
				this.stack.push(value);
			} else if ((varNumber >= 1) && (varNumber <= 15)) {
				setLocalVariableValue(varNumber, value);
			} else if ((varNumber >= 16) && (varNumber <= 255)) {
				setGlobalVariableValue(varNumber, value);
			} else {
				halt(String.format("setVariableValue() - Variable number %d out of bounds [%d..%d]", varNumber, 1, 255));
			}
		}

		private void setLocalVariableValue(int localVarNumber /* 1..15 */, int value) {
			int localIndex = getLocalVariableIndex(localVarNumber);
			this.stack.poke(localIndex, value);
		}

		private void setGlobalVariableValue(int globalVarNumber /* 16..255 */, int value) {
			int globalAddr = getGlobalVariableAddress(globalVarNumber);
			setWord(globalAddr, value);
		}

		// call/return

		public void zmcall(int[] args) {
			// arg[0] = packed routine address, arg[1]..[3] = word arguments

			int routineAddr = getUnpackedAddress(args[0]);
			if (routineAddr >= this.header.lengthOfFile) {
				halt(String.format("zmcall() - Called routine at 0x%x outside of story file", routineAddr));
			}

			int numLocals = getByte(routineAddr);
			if (numLocals > 15) {
				halt(String.format("zmcall() - Called routine at 0x%x not a routine", routineAddr));
			}

			if (routineAddr == 0) {
				consumeAndStore(0);
				return;
			}

			int routineCodeAddr = routineAddr + 1 + (numLocals * WORD_SIZE);

			this.stack.pushInt32(this.pc);
			this.stack.push(this.stack.stackFrameIndex);
			this.stack.stackFrameIndex = this.stack.topIndex;
			this.stack.push(numLocals);
			for (int i = 1; i <= numLocals; i++) {
				int defaultValueAddr = routineAddr + 1 + ((i - 1) * WORD_SIZE);
				int defaultValue = getWord(defaultValueAddr);
				int value = (i < args.length) ? args[i] : defaultValue;
				this.stack.push(value);
			}

			this.pc = routineCodeAddr;
			// implicit store operation done in zmreturn()
		}

		public void zmreturn(int arg) {
			if (this.stack.stackFrameIndex == -1) {
				halt("zmreturn() - Call stack underflow");
			}

			this.stack.topIndex = this.stack.stackFrameIndex;
			this.stack.stackFrameIndex = this.stack.pop();
			this.pc = this.stack.popInt32();

			consumeAndStore(arg);
		}

		// objects

		//	Object table (objects are numbered 1..255, index 0 is like NIL)
		//
		//	    +---------+ A = 32 Bit attributes numbered 0..31
		//	  1 |AAAABCDEE| B = Parent object ID
		//	... |         | C = Sibling object ID
		//	255 |         | D = (First) child object ID
		//	    +---------+ E = Pointer to property
		//
		//	Property
		//
		//	+----------+ F = # Words of object text
		//	|F|GG....GG| G = Object text
		//	|H|I...I|    H = See below
		//	|    ...   | I = (J + 1) Property bytes
		//	|0|
		//	+-+
		//
		//	H in bits
		//
		//	+--------+ J = # Property bytes - 1
		//	|JJJKKKKK| K = Property number (1..31)
		//	+--------+

		private static int NUM_PROPERTIES = 31;
		private static int OBJECT_ELEMENT_SIZE = 4 + 3 + 2;

		public int getObjectAddress(int objNumber) {
			if ((objNumber < 1) || (objNumber > 255)) {
				halt(String.format("getObjectAddress() - Object number %d out of bounds [%d..%d]", objNumber, 1, 255));
			}

			int objAddr = this.header.objectTableAddr + (NUM_PROPERTIES * WORD_SIZE) + ((objNumber - 1) * OBJECT_ELEMENT_SIZE);
			return objAddr;
		}

		//	+-------+  P = Parent link
		//	|       |  S = Sibling link
		//	++------+  C = (First) Child link
		//	C|  ^ ^ ^
		//	 |  | | |
		//	 |  | | +---------------------+
		//	 |  | +----------+            |
		//	 V  |P           |P           |P
		//	+---+---+    +---+---+    +---+---+
		//	|       +--->|       +--->|       |
		//	+-------+ S  +-------+ S  +-------+

		private static final int OFFSET_PARENT = 4;
		private static final int OFFSET_SIBLING = 5;
		private static final int OFFSET_FIRST_CHILD = 6;

		public int getParentNumber(int objNumber) {
			int objAddr = getObjectAddress(objNumber);
			int parentNumber = getByte(objAddr + OFFSET_PARENT);
			return parentNumber;
		}

		public void setParentNumber(int objNumber, int parentNumber) {
			int objAddr = getObjectAddress(objNumber);
			setByte(objAddr + OFFSET_PARENT, parentNumber);
		}

		public int getSiblingNumber(int objNumber) {
			int objAddr = getObjectAddress(objNumber);
			int siblingNumber = getByte(objAddr + OFFSET_SIBLING);
			return siblingNumber;
		}

		public void setSiblingNumber(int objNumber, int siblingNumber) {
			int objAddr = getObjectAddress(objNumber);
			setByte(objAddr + OFFSET_SIBLING, siblingNumber);
		}

		public int getChildNumber(int objNumber) {
			int objAddr = getObjectAddress(objNumber);
			int childNumber = getByte(objAddr + OFFSET_FIRST_CHILD);
			return childNumber;
		}

		public void setChildNumber(int objNumber, int childNumber) {
			int objAddr = getObjectAddress(objNumber);
			setByte(objAddr + OFFSET_FIRST_CHILD, childNumber);
		}

		// decoding/encoding strings

		private final static String ALPHABET = "" //
				+ "abcdefghijklmnopqrstuvwxyz" //
				+ "ABCDEFGHIJKLMNOPQRSTUVWXYZ"//
				+ "*" + EOL + "0123456789.,!?_#'\"/\\-:()";

		public String decodeZString(int index) {
			List<Integer> zchars = new ArrayList<Integer>();

			boolean isDone = false;
			do {
				int iZchars = getWord(index);
				isDone = isBitSet(iZchars, 15);
				zchars.add((iZchars >> 10) & 0b1_1111);
				zchars.add((iZchars >> 5) & 0b1_1111);
				zchars.add(iZchars & 0b1_1111);
				index += 2;
			} while (isDone == false);

			int currAlphabet = 0;
			StringBuffer result = new StringBuffer();
			for (int i = 0; i < zchars.size(); i++) {
				int zchar = zchars.get(i);
				if (zchar == 0) {
					result.append(" ");
				} else if ((zchar >= 1) && (zchar <= 3)) {
					int zchar2 = zchars.get(i + 1);
					i++;
					int abbrIndex = (32 * (zchar - 1)) + zchar2;
					int strAddr = getAbbreviationAddress(abbrIndex);
					result.append(decodeZString(strAddr));
				} else if (zchar == 4) {
					currAlphabet = 1;
					continue;
				} else if (zchar == 5) {
					currAlphabet = 2;
					continue;
				} else if ((zchar == 6) && (currAlphabet == 2)) {
					int zchar2 = zchars.get(i + 1);
					int zchar3 = zchars.get(i + 2);
					i += 2;
					result.append((char) ((zchar2 << 5) | zchar3));
				} else {
					result.append(ALPHABET.charAt(((currAlphabet * 26) + zchar) - 6));
				}
				currAlphabet = 0;
			}

			return result.toString();
		}

		private byte[] encodeZString(String text) {
			List<Integer> zchars = new ArrayList<Integer>();

			text = text.toLowerCase(); // duplicate toLowerCase(), just to make sure
			int textLen = Math.min(text.length(), 6);
			for (int i = 0; i < textLen; i++) {
				char chr = text.charAt(i);
				if (chr == ' ') {
					zchars.add(0);
				} else {
					int pos = ALPHABET.indexOf(chr);
					if ((pos >= 0) & (pos <= 25)) {
						zchars.add(6 + pos);
					} else if ((pos >= 52) & (pos <= 77)) {
						zchars.add(0b0101);
						zchars.add((6 + pos) - 52);
					} else {
						// ignore
					}
				}
			}

			while (zchars.size() < 6) {
				zchars.add(0b0101);
			}

			byte[] result = new byte[4];

			int index = 0;
			int zcharIndex = 0;
			while (index < 4) {
				int zchar1 = zchars.get(zcharIndex++);
				int zchar2 = zchars.get(zcharIndex++);
				int zchar3 = zchars.get(zcharIndex++);

				int word = (zchar1 << 10) | (zchar2 << 5) | zchar3;
				result[index] = (byte) ((word >> 8) & 0xFF);
				result[index + 1] = (byte) (word & 0xFF);
				index += 2;
			}

			result[2] |= 0b1000_0000;
			return result;
		}

		public int getWordAddr(String word) { // TODO: Use binary search
			byte[] wordToSearch = encodeZString(word);

			int dictionaryAddr = this.header.dictionaryAddr;
			int numSeparators = getByte(dictionaryAddr);
			int entryLen = getByte(dictionaryAddr + 1 + numSeparators);
			int numEntries = getWord(dictionaryAddr + 1 + numSeparators + 1);

			int wordAddr = dictionaryAddr + 1 + numSeparators + 1 + 2;

			for (int i = 0; i < numEntries; i++) {
				boolean isFound = true;
				for (int j = 0; j < 4; j++) {
					byte charDictionaryWord = (byte) getByte(wordAddr + j);
					if (charDictionaryWord != wordToSearch[j]) {
						isFound = false;
						break;
					}
				}
				if (isFound) {
					return wordAddr;
				}
				wordAddr += entryLen;
			}
			return 0;
		}

		private String WORD_SEPARATORS = null;

		public String getWordSeparators() {
			if (this.WORD_SEPARATORS == null) {
				int dictionaryAddr = this.header.dictionaryAddr;
				int numSeparators = getByte(dictionaryAddr);

				StringBuffer buffer = new StringBuffer();
				for (int i = 0; i < numSeparators; i++) {
					buffer.append((char) getByte(dictionaryAddr + 1 + i));
				}
				this.WORD_SEPARATORS = buffer.toString();
			}
			return this.WORD_SEPARATORS;
		}

		private int getAbbreviationAddress(int abbrIndex) {
			if ((abbrIndex < 0) || (abbrIndex > 95)) {
				halt(String.format("getAbbreviationAddress() - Index %d out of bounds [%d..%d]", abbrIndex, 0, 95));
			}
			int abbrAddr = this.header.abbreviationTableAddr + (abbrIndex * WORD_SIZE);
			return getUnpackedAddress(getWord(abbrAddr));
		}

		// random number generator

		private enum RandomNumberGeneratorState {
			RANDOM, //
			PREDICTABLE
		}

		private RandomNumberGeneratorState rngState = RandomNumberGeneratorState.RANDOM;
		private int rngSeed;
		private int rngCounter;

		public int random(int value) {
			int result = -1;

			if (value == 0) {
				this.rngState = RandomNumberGeneratorState.RANDOM;
				return 0; // unsure, if this is correct
			} else if (value < 0) {
				this.rngState = RandomNumberGeneratorState.PREDICTABLE;
				this.rngSeed = -value;
				this.rngCounter = -1;
			}

			if (this.rngState == RandomNumberGeneratorState.RANDOM) {
				result = (int) (Math.random() * value) + 1;
			} else {
				this.rngCounter++;
				result = (this.rngCounter % this.rngSeed) + 1;
			}

			return result;
		}

		// testing memory areas

		public boolean isDynamicMemory(int addr) {
			int minDynamicMemory = 0;
			int maxDynamicMemory = this.header.baseStaticMemoryAddr - 1;
			boolean isDynamicMemory = (addr >= minDynamicMemory) && (addr <= maxDynamicMemory);
			return isDynamicMemory;
		}

		public boolean isDynamicOrStaticMemory(int addr) {
			int bounds = Math.min(0xFFFF, this.story.length);
			boolean isDynamicOrStaticMemory = addr <= bounds;
			return isDynamicOrStaticMemory;
		}

		public boolean isHighMemory(int addr) {
			boolean isHighMemory = addr >= this.header.baseHighMemoryAddr;
			return isHighMemory;
		}

		//

		public int getUnpackedAddress(int packedAddr) {
			return packedAddr * 2;
		}

		private void halt(String errorMessage) {
			throw new RuntimeException("Z-Machine halted: " + errorMessage);
		}
	}

	//////////////////////////////////////////////////////////////////////////////

	private ZMachine zm;
	private Path storyFilePath;
	private boolean isShowScoreUpdates;
	private Scanner scanner;
	private StringBuffer buffer;
	private int oldScore;

	public ZInterpreter(Path storyFilePath, boolean isShowScoreUpdates) {
		this.storyFilePath = storyFilePath;
		this.isShowScoreUpdates = isShowScoreUpdates;
		this.scanner = new Scanner(System.in);
		this.buffer = new StringBuffer();
		this.oldScore = 0;
	}

	private void restoreScore() {
		this.oldScore = this.zm.toInt32(this.zm.getVariableValue(17));
	}

	private void checkForScoreUpdate() {
		if (this.isShowScoreUpdates == false) {
			return;
		}

		if (this.zm.isBitClear(this.zm.header.flags1, 1)) {
			int newScore = this.zm.toInt32(this.zm.getVariableValue(17));
			int scoreDelta = newScore - this.oldScore;
			this.oldScore = newScore;

			String scoreString = null;
			if (scoreDelta > 0) {
				scoreString = String.format("[Your score increased by %d points. Your current score is %d points.]" + ZMachine.EOL + ZMachine.EOL, scoreDelta, newScore);
			} else if (scoreDelta < 0) {
				scoreString = String.format("[Your score decreased by %d points. Your current score is %d points.]" + ZMachine.EOL + ZMachine.EOL, -scoreDelta, newScore);
			}
			if (scoreString != null) {
				int pos = this.buffer.length() - 1;
				if (this.buffer.substring(pos).equals(">")) {
					this.buffer.insert(pos, scoreString);
				}
			}
		}
	}

	private String getInput() {
		checkForScoreUpdate();
		flush();
		return this.scanner.nextLine();
	}

	private void print(String text) {
		this.buffer.append(text);
	}

	private void flush() {
		final int MAX_LINE_WIDTH = 80;

		String str = this.buffer.toString();
		int pos = 0;
		while (pos < str.length()) {
			int posEol = str.indexOf(ZMachine.EOL, pos);
			if (posEol >= 0) {
				wrap(str, pos, posEol, MAX_LINE_WIDTH);
				System.out.print(CR);
				pos = posEol + 1;
			} else {
				wrap(str, pos, str.length(), MAX_LINE_WIDTH);
				pos = str.length();
			}
		}

		this.buffer.setLength(0);
	}

	private static void wrap(String str, int startPos, int endPos, int maxChars) {
		int posLineStart = startPos;
		int i = startPos;
		while (i < endPos) {
			int posNextWord = endPos;
			for (int j = i; j < endPos; j++) {
				if (str.charAt(j) != ' ') {
					posNextWord = j;
					break;
				}
			}

			int posNextSpace = endPos;
			for (int j = posNextWord; j < endPos; j++) {
				if (str.charAt(j) == ' ') {
					posNextSpace = j;
					break;
				}
			}

			if ((posNextSpace - posLineStart) <= maxChars) {
				System.out.print(str.substring(i, posNextSpace));
				i = posNextSpace;
			} else {
				if (posNextWord == posLineStart) {
					System.out.print(str.substring(i, i + maxChars));
					i = i + maxChars;
					if (i < endPos) {
						System.out.print(CR);
						posLineStart = i;
					}
				} else {
					i = posNextWord;
					posLineStart = posNextWord;
					System.out.print(CR);
				}
			}
		}
	}

	// 0OP instructions

	private void Z_rtrue() {
		Z_ret(1);
	}

	private void Z_rfalse() {
		Z_ret(0);
	}

	private void Z_print() {
		String str = this.zm.consumeString();
		print(str);
	}

	private void Z_print_ret() {
		Z_print();
		Z_new_line();
		Z_ret(1);
	}

	private void Z_nop() {
		// ignore
	}

	private void Z_save() { // BRANCH OP
		boolean isBranch = true;

		print("File to save? >");
		String strSaveFilepath = getInput();

		String saveContent = createSaveContent();

		try (BufferedWriter out = new BufferedWriter(new FileWriter(strSaveFilepath))) {
			out.write(saveContent);
		} catch (IOException e) {
			isBranch = false;
		}
		this.zm.consumeAndBranch(isBranch);
	}

	private String createSaveContent() {
		final int NUM_BYTES_IN_ROW = 40;

		StringBuffer result = new StringBuffer();

		result.append("releasenumber.serialcode" + CR);
		result.append(String.format("%02d.%6s", this.zm.header.releaseNumber, this.zm.header.serialCode) + CR);

		result.append("pc" + CR);
		result.append(String.format("%04x", this.zm.pc) + CR);

		result.append("stack" + CR);
		result.append(String.format("%04x", this.zm.stack.topIndex + 1) + CR);
		for (int i = 0; i < (this.zm.stack.topIndex + 1); i++) {
			if ((i > 0) && ((i % NUM_BYTES_IN_ROW) == 0)) {
				result.append(CR);
			}
			result.append(String.format("%04x ", this.zm.stack.peek(i) & 0xFFFF));
		}
		result.append(CR);

		result.append("stack.topindex" + CR);
		result.append(String.format("%04x", this.zm.stack.topIndex) + CR);

		result.append("stack.stackframeindex" + CR);
		result.append(String.format("%04x", this.zm.stack.stackFrameIndex) + CR);

		result.append("dynamicmemory" + CR);
		result.append(String.format("%04x", this.zm.header.baseStaticMemoryAddr) + CR);
		for (int i = 0; i < this.zm.header.baseStaticMemoryAddr; i++) {
			if ((i > 0) && ((i % NUM_BYTES_IN_ROW) == 0)) {
				result.append(CR);
			}
			result.append(String.format("%02x ", this.zm.getByte(i)));
		}

		return result.toString();
	}

	private void Z_restore() { // BRANCH OP
		boolean isBranch = true;

		print("File to restore? >");
		String strRestoreFilepath = getInput();

		List<String> lines = null;
		try {
			lines = Files.readAllLines(new File(strRestoreFilepath).toPath(), StandardCharsets.US_ASCII);
		} catch (IOException e) {
			isBranch = false;
		}

		int newPc = -1;
		int newStackTopIndex = -1;
		int newStackFrameIndex = -1;
		int[] newStack = null;
		byte[] newDynamicMemory = null;

		if (isBranch && (lines != null)) {
			int lineCnt = 0;
			while (lineCnt < lines.size()) {
				String line = lines.get(lineCnt);

				if (line.equals("releasenumber.serialcode")) {
					lineCnt++;
					String version = String.format("%02d.%6s", this.zm.header.releaseNumber, this.zm.header.serialCode);
					String versionToCompare = lines.get(lineCnt);
					if (version.equals(versionToCompare) == false) {
						isBranch = false;
						break;
					}
				} else if (line.equals("pc")) {
					lineCnt++;
					newPc = Integer.parseInt(lines.get(lineCnt), 16);
				} else if (line.equals("stack.topindex")) {
					lineCnt++;
					newStackTopIndex = Integer.parseInt(lines.get(lineCnt), 16);
				} else if (line.equals("stack.stackframeindex")) {
					lineCnt++;
					newStackFrameIndex = Integer.parseInt(lines.get(lineCnt), 16);
				} else if (line.equals("stack")) {
					lineCnt++;
					int newStackLen = Integer.parseInt(lines.get(lineCnt), 16);
					newStack = new int[newStackLen];

					int j = 0;
					lineCnt++;
					while (lineCnt < lines.size()) {
						String[] aStr = lines.get(lineCnt).split(" ");
						if (isHexNumber(aStr[0]) == false) {
							lineCnt--;
							break;
						}
						for (int i = 0; i < aStr.length; i++) {
							newStack[j] = Integer.parseInt(aStr[i], 16);
							j++;
						}
						lineCnt++;
					}
				} else if (line.equals("dynamicmemory")) {
					lineCnt++;
					int newDynamicMemoryLen = Integer.parseInt(lines.get(lineCnt), 16);
					newDynamicMemory = new byte[newDynamicMemoryLen];

					int j = 0;
					lineCnt++;
					while (lineCnt < lines.size()) {
						String[] aStr = lines.get(lineCnt).split(" ");
						if (isHexNumber(aStr[0]) == false) {
							lineCnt--;
							break;
						}

						for (int i = 0; i < aStr.length; i++) {
							newDynamicMemory[j] = (byte) Integer.parseInt(aStr[i], 16);
							j++;
						}
						lineCnt++;
					}
				}
				lineCnt++;
			}
		}

		boolean isComplete = (newPc != -1) && (newStackTopIndex != -1) && (newStackFrameIndex != -1) && ((newStack != null) & (newDynamicMemory != null));
		if (isComplete) {
			this.zm.pc = newPc;
			this.zm.stack.topIndex = newStackTopIndex;
			this.zm.stack.stackFrameIndex = newStackFrameIndex;
			System.arraycopy(newStack, 0, this.zm.stack.stack, 0, newStack.length);
			System.arraycopy(newDynamicMemory, 0, this.zm.story, 0, newDynamicMemory.length);
		} else {
			isBranch = false;
		}

		this.zm.consumeAndBranch(isBranch);

		restoreScore();
	}

	private boolean isHexNumber(String str) {
		try {
			Integer.parseInt(str, 16);
		} catch (NumberFormatException e) {
			return false;
		}
		return true;
	}

	private void Z_restart() {
		try {
			byte[] story = Files.readAllBytes(this.storyFilePath);
			System.arraycopy(story, 0, this.zm.story, 0, story.length);
			this.zm.stack.reset();
			this.zm.pc = this.zm.header.initialPC;
		} catch (IOException e) {
			// ignore
		}

		restoreScore();
	}

	private void Z_ret_popped() {
		int value = this.zm.stack.pop();
		Z_ret(value);
	}

	private void Z_pop() {
		this.zm.stack.pop();
	}

	private void Z_quit() {
		this.zm.isRunning = false;
		flush();
	}

	private void Z_new_line() {
		print(ZMachine.EOL);
	}

	private void Z_show_status() {
		// ignore
	}

	private void Z_verify() { // BRANCH OP
		// ignore
		boolean isBranch = true;
		this.zm.consumeAndBranch(isBranch);
	}

	// 1OP instructions

	private void Z_jz(int arg) { // BRANCH OP
		boolean isBranch = (arg == 0);
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_get_sibling(int arg) { // STORE + BRANCH OP
		int siblingNumber = this.zm.getSiblingNumber(arg);
		this.zm.consumeAndStore(siblingNumber);

		boolean isBranch = siblingNumber != 0;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_get_child(int arg) { // STORE + BRANCH OP
		int childNumber = this.zm.getChildNumber(arg);
		this.zm.consumeAndStore(childNumber);

		boolean isBranch = childNumber != 0;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_get_parent(int arg) { // STORE OP
		int parentNumber = this.zm.getParentNumber(arg);
		this.zm.consumeAndStore(parentNumber);
	}

	private void Z_get_prop_len(int arg) { // STORE OP
		int propertyAddr = arg;
		int value = 0;
		if (propertyAddr != 0) {
			value = (this.zm.getByte(propertyAddr - 1) >> 5) + 1;
		}
		this.zm.consumeAndStore(value);
	}

	private void Z_inc(int arg) {
		int value = this.zm.getVariableValue(arg);
		int valueInt32 = this.zm.toInt32(value);
		int result = this.zm.toUint16(valueInt32 + 1);
		this.zm.setVariableValue(arg, result);
	}

	private void Z_dec(int arg) {
		int value = this.zm.getVariableValue(arg);
		int valueInt32 = this.zm.toInt32(value);
		int result = this.zm.toUint16(valueInt32 - 1);
		this.zm.setVariableValue(arg, result);
	}

	private void Z_print_addr(int arg) {
		if (this.zm.isDynamicOrStaticMemory(arg)) {
			String str = this.zm.decodeZString(arg);
			print(str);
		} else {
			halt(String.format("Z_print_addr() - Address 0x%x not in dynamic or static memory", arg));
		}
	}

	private void Z_remove_obj(int arg) {
		int objNumber = arg;

		if (objNumber == 0) {
			halt("Z_remove_obj() - Remove object number 0");
		}

		int parentNumber = this.zm.getParentNumber(objNumber);
		if (parentNumber == 0) {
			return;
		}

		int siblingNumber = this.zm.getSiblingNumber(objNumber);
		int childOfParentNumber = this.zm.getChildNumber(parentNumber);

		boolean isFirstChildOfParent = (objNumber == childOfParentNumber);
		if (isFirstChildOfParent) {
			this.zm.setChildNumber(parentNumber, siblingNumber);
		} else {
			int prevObjNumber = childOfParentNumber;
			while (this.zm.getSiblingNumber(prevObjNumber) != objNumber) {
				prevObjNumber = this.zm.getSiblingNumber(prevObjNumber);
			}
			this.zm.setSiblingNumber(prevObjNumber, siblingNumber);
		}
		this.zm.setSiblingNumber(objNumber, 0);
		this.zm.setParentNumber(objNumber, 0);
	}

	private void Z_print_obj(int arg) {
		int objAddr = this.zm.getObjectAddress(arg);
		int propAddr = this.zm.getWord(objAddr + 7);
		String str = this.zm.decodeZString(propAddr + 1);
		print(str);
	}

	private void Z_ret(int arg) {
		this.zm.zmreturn(arg);
	}

	private void Z_jump(int arg) {
		int offset = this.zm.toInt32(arg);
		this.zm.pc = (this.zm.pc + offset) - 2;
	}

	private void Z_print_paddr(int arg) {
		int addr = this.zm.getUnpackedAddress(arg);
		if (this.zm.isHighMemory(addr)) {
			String str = this.zm.decodeZString(addr);
			print(str);
		} else {
			halt(String.format("Z_print_paddr() - Address 0x%x not in high memory", arg));
		}
	}

	private void Z_load(int arg) { // STORE OP
		int value = this.zm.getVariableValue(arg);
		this.zm.consumeAndStore(value);
	}

	private void Z_not(int arg) { // STORE OP
		int value = this.zm.toUint16(arg);
		int result = value ^ 0xFFFF;
		this.zm.consumeAndStore(result);
	}

	// 2OP instructions

	// NOTE: JE can take 2..4 operands
	private void Z_je(int args[]) { // BRANCH OP
		boolean isBranch = false;
		for (int i = 1; i < args.length; i++) {
			if (args[0] == args[i]) {
				isBranch = true;
				break;
			}
		}
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_jl(int args[]) { // BRANCH OP
		int value1 = this.zm.toInt32(args[0]);
		int value2 = this.zm.toInt32(args[1]);
		boolean isBranch = value1 < value2;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_jg(int args[]) { // BRANCH OP
		int value1 = this.zm.toInt32(args[0]);
		int value2 = this.zm.toInt32(args[1]);
		boolean isBranch = value1 > value2;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_dec_chk(int args[]) { // BRANCH OP
		int varNumber = args[0];
		int value = args[1];

		Z_dec(varNumber);
		int value1 = this.zm.toInt32(this.zm.getVariableValue(varNumber));
		int value2 = this.zm.toInt32(value);
		boolean isBranch = value1 < value2;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_inc_chk(int args[]) { // BRANCH OP
		int varNumber = args[0];
		int value = args[1];

		Z_inc(varNumber);
		int value1 = this.zm.toInt32(this.zm.getVariableValue(varNumber));
		int value2 = this.zm.toInt32(value);
		boolean isBranch = value1 > value2;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_jin(int args[]) { // BRANCH OP
		int objNumberChild = args[0];
		int objNumberParent = args[1];

		int objNumberParentOfChild = this.zm.getParentNumber(objNumberChild);
		boolean isBranch = objNumberParentOfChild == objNumberParent;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_test(int args[]) { // BRANCH OP
		int bitmap = this.zm.toUint16(args[0]); // is conversion necessary?
		int flags = this.zm.toUint16(args[1]);
		boolean isBranch = (bitmap & flags) == flags;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_or(int args[]) { // STORE OP
		int value1 = this.zm.toUint16(args[0]);
		int value2 = this.zm.toUint16(args[1]);
		int result = value1 | value2;
		this.zm.consumeAndStore(result);
	}

	private void Z_and(int args[]) { // STORE OP
		int value1 = this.zm.toUint16(args[0]);
		int value2 = this.zm.toUint16(args[1]);
		int result = value1 & value2;
		this.zm.consumeAndStore(result);
	}

	private void Z_test_attr(int args[]) { // BRANCH OP
		int objNumber = args[0];
		int bitNumber = args[1];
		if ((bitNumber < 0) || (bitNumber > 31)) {
			halt(String.format("Z_test_attr() - Bit number %d out of bounds [%d..%d]", bitNumber, 0, 31));
		}

		int objAddr = this.zm.getObjectAddress(objNumber);
		int byteOffset = bitNumber >> 3;
		int mask = 1 << (7 - (bitNumber & 0b111));

		int aByte = this.zm.getByte(objAddr + byteOffset);
		boolean isBranch = (aByte & mask) != 0;
		this.zm.consumeAndBranch(isBranch);
	}

	private void Z_set_attr(int args[]) {
		int objNumber = args[0];
		int bitNumber = args[1];
		if ((bitNumber < 0) || (bitNumber > 31)) {
			halt(String.format("Z_set_attr() - Bit number %d out of bounds [%d..%d]", bitNumber, 0, 31));
		}

		int objAddr = this.zm.getObjectAddress(objNumber);
		int byteOffset = bitNumber >> 3;
		int mask = 1 << (7 - (bitNumber & 0b111));

		int aByte = this.zm.getByte(objAddr + byteOffset);
		this.zm.setByte(objAddr + byteOffset, aByte | mask);
	}

	private void Z_clear_attr(int args[]) {
		int objNumber = args[0];
		int bitNumber = args[1];
		if ((bitNumber < 0) || (bitNumber > 31)) {
			halt(String.format("Z_set_attr() - Bit number %d out of bounds [%d..%d]", bitNumber, 0, 31));
		}

		int objAddr = this.zm.getObjectAddress(objNumber);
		int byteOffset = bitNumber >> 3;
		int mask = 1 << (7 - (bitNumber & 0b111));

		int aByte = this.zm.getByte(objAddr + byteOffset);
		this.zm.setByte(objAddr + byteOffset, aByte & ~mask);
	}

	private void Z_store(int args[]) {
		int varNumber = args[0];
		int value = args[1];
		this.zm.setVariableValue(varNumber, value);
	}

	private void Z_insert_obj(int args[]) {
		int objNumber = args[0];
		int destObjNumber = args[1];
		if (objNumber == destObjNumber) {
			halt(String.format("Z_insert_obj() - Insert object number %d to itself", objNumber));
		}

		if (destObjNumber == this.zm.getParentNumber(objNumber)) {
			return;
		}

		Z_remove_obj(objNumber);

		this.zm.setSiblingNumber(objNumber, this.zm.getChildNumber(destObjNumber));
		this.zm.setChildNumber(destObjNumber, objNumber);
		this.zm.setParentNumber(objNumber, destObjNumber);
	}

	private void Z_loadw(int args[]) { // STORE OP
		int addr = args[0] + (args[1] * ZMachine.WORD_SIZE);
		if (this.zm.isDynamicOrStaticMemory(addr)) {
			int result = this.zm.getWord(addr);
			this.zm.consumeAndStore(result);
		} else {
			halt(String.format("Z_loadw() - Address 0x%x not in dynamic or static memory", addr));
		}
	}

	private void Z_loadb(int args[]) { // STORE OP
		int addr = args[0] + args[1];
		if (this.zm.isDynamicOrStaticMemory(addr)) {
			int result = this.zm.getByte(addr);
			this.zm.consumeAndStore(result);
		} else {
			halt(String.format("Z_loadb() - Address 0x%x not in dynamic or static memory", addr));
		}
	}

	private void Z_get_prop(int args[]) { // STORE OP
		int objNumber = args[0];
		int propNumber = args[1];

		int propValue = -1;
		int propAddr = getPropAddress(objNumber, propNumber, /* isAcceptPropNumberZero */ false);
		if (propAddr != 0) {
			int propDescByte = this.zm.getByte(propAddr);
			int propLen = (propDescByte >> 5) + 1;
			if (propLen == 1) {
				propValue = this.zm.getByte(propAddr + 1);
			} else if (propLen == 2) {
				propValue = this.zm.getWord(propAddr + 1);
			} else {
				halt(String.format("Z_get_prop() - Property length %d of property %d of object %d out of bounds [%d..%d]", propLen, propNumber, objNumber, 1, 2));
			}
		} else {
			int defaultValueAddr = this.zm.header.objectTableAddr + ((propNumber - 1) * ZMachine.WORD_SIZE);
			propValue = this.zm.getWord(defaultValueAddr);
		}
		this.zm.consumeAndStore(propValue);
	}

	private void Z_get_prop_addr(int args[]) { // STORE OP
		int objNumber = args[0];
		int propNumber = args[1];

		int propAddr = getPropAddress(objNumber, propNumber, /* isAcceptPropNumberZero */ false);
		int value = (propAddr != 0) ? propAddr + 1 : 0;
		this.zm.consumeAndStore(value);
	}

	private void Z_get_next_prop(int args[]) { // STORE OP
		int objNumber = args[0];
		int propNumber = args[1];

		int propAddr = getPropAddress(objNumber, propNumber, /* isAcceptPropNumberZero */ true);
		if (propAddr == 0) {
			halt(String.format("Z_get_next_prop() - Next property of property number %d of object %d not found", propNumber, objNumber));
		}

		int propValue = -1;
		if (propNumber == 0) {
			propValue = this.zm.getByte(propAddr) & 0b1_1111;
		} else {
			int propDescByte = this.zm.getByte(propAddr);
			int propLen = (propDescByte >> 5) + 1;
			int nextPropAddr = propAddr + 1 + propLen;
			int nextPropDescByte = this.zm.getByte(nextPropAddr);
			propValue = nextPropDescByte & 0b1_1111;
		}
		this.zm.consumeAndStore(propValue);
	}

	private void Z_add(int args[]) { // STORE OP
		int value1 = this.zm.toInt32(args[0]);
		int value2 = this.zm.toInt32(args[1]);
		int result = this.zm.toUint16(value1 + value2);
		this.zm.consumeAndStore(result);
	}

	private void Z_sub(int args[]) { // STORE OP
		int value1 = this.zm.toInt32(args[0]);
		int value2 = this.zm.toInt32(args[1]);
		int result = this.zm.toUint16(value1 - value2);
		this.zm.consumeAndStore(result);
	}

	private void Z_mul(int args[]) { // STORE OP
		int value1 = this.zm.toInt32(args[0]);
		int value2 = this.zm.toInt32(args[1]);
		int result = this.zm.toUint16(value1 * value2);
		this.zm.consumeAndStore(result);
	}

	private void Z_div(int args[]) { // STORE OP
		int value1 = this.zm.toInt32(args[0]);
		int value2 = this.zm.toInt32(args[1]);
		if (value2 == 0) {
			halt("Z_div() - Divison by zero");
		}

		int result = this.zm.toUint16(value1 / value2);
		this.zm.consumeAndStore(result);
	}

	private void Z_mod(int args[]) {// STORE OP
		int value1 = this.zm.toInt32(args[0]);
		int value2 = this.zm.toInt32(args[1]);
		if (value2 == 0) {
			halt("Z_mod() - Modulo division by zero");
		}

		int result = this.zm.toUint16(value1 % value2);
		this.zm.consumeAndStore(result);
	}

	// VAR instructions

	private void Z_call(int args[]) { // STORE OP
		this.zm.zmcall(args);
		// store is done in Z_ret()
	}

	private void Z_storew(int args[]) {
		int addr = args[0] + (args[1] * ZMachine.WORD_SIZE);
		int value = args[2];

		if (this.zm.isDynamicMemory(addr)) {
			this.zm.setWord(addr, value);
		} else {
			halt(String.format("Z_storew() - Address 0x%x not in dynamic memory", addr));
		}
	}

	private void Z_storeb(int args[]) {
		int addr = args[0] + args[1];
		int value = args[2];

		if (this.zm.isDynamicMemory(addr)) {
			this.zm.setByte(addr, value);
		} else {
			halt(String.format("Z_storeb() - Address 0x%x not in dynamic memory", addr));
		}
	}

	private int getPropAddress(int objNumber, int propNumber, boolean isAcceptPropNumberZero) {
		int objAddr = this.zm.getObjectAddress(objNumber);

		int minAcceptedPropNumber = isAcceptPropNumberZero ? 0 : 1;
		if ((propNumber < minAcceptedPropNumber) || (propNumber > 255)) {
			halt(String.format("getPropAddress() - Property number %d of object %d out of bounds [%d..%d]", propNumber, objNumber, minAcceptedPropNumber, 31));
		}

		int propAddr = this.zm.getWord(objAddr + 7);
		int nameLength = this.zm.getByte(propAddr);
		propAddr += 1 + (nameLength * 2);

		if (isAcceptPropNumberZero && (propNumber == 0)) {
			return propAddr;
		}

		while (true) {
			int propDescByte = this.zm.getByte(propAddr);
			if (propDescByte == 0) {
				return 0;
			}

			int propNumberToCompare = propDescByte & 0b1_1111;
			if (propNumber == propNumberToCompare) {
				return propAddr;
			}

			int propLen = (propDescByte >> 5) + 1;
			propAddr += 1 + propLen;
		}
	}

	private void Z_put_prop(int args[]) {
		int objNumber = args[0];
		int propNumber = args[1];
		int value = args[2];

		int propAddr = getPropAddress(objNumber, propNumber, /* isAcceptZeroPropNumber */ false);
		if (propAddr != 0) {
			int propDescByte = this.zm.getByte(propAddr);
			int propLen = (propDescByte >> 5) + 1;
			if (propLen == 1) {
				this.zm.setByte(propAddr + 1, value);
			} else if (propLen == 2) {
				this.zm.setWord(propAddr + 1, value);
			} else {
				halt(String.format("Z_put_prop() - Property length %d of property %d of object %d out of bounds [%d..%d]", propLen, propNumber, objNumber, 1, 2));
			}
		} else {
			halt(String.format("Z_put_prop() - Property number %d of object %d not found", propNumber, objNumber));
		}
	}

	private void Z_sread(int args[]) {
		int textAddr = args[0];
		int parseAddr = args[1];

		if (this.zm.getByte(textAddr) < 3) {
			halt("Z_sread() - Text buffer less than 3 bytes long");
		}

		// TODO: Show status line

		String input = getInput().toLowerCase().trim();

		int maxInputLen = this.zm.getByte(textAddr) - 1;
		int maxLen = Math.min(maxInputLen, input.length());
		for (int i = 0; i < maxLen; i++) {
			int chr = input.charAt(i);
			this.zm.setByte(textAddr + 1 + i, (byte) chr);
		}
		this.zm.setByte(textAddr + 1 + maxLen, 0);

		int maxWordsToParse = this.zm.getByte(parseAddr);
		if (maxWordsToParse < 1) {
			halt("Z_sread() - Parse buffer less than 1 word long");
		}

		String wordSeparators = this.zm.getWordSeparators();
		List<Integer> wordStartPos = new ArrayList<Integer>();
		List<String> wordTexts = new ArrayList<String>();

		int pos = 0;
		int startPos = -1;
		boolean isAddCharacters = false;

		while (pos < input.length()) {
			String chr = input.substring(pos, pos + 1);

			boolean isWordSeparator = wordSeparators.indexOf(chr) > -1;
			boolean isWhitespace = chr.equals(" ");

			if (isWordSeparator || isWhitespace) {
				if (isAddCharacters) {
					wordStartPos.add(startPos);
					wordTexts.add(input.substring(startPos, pos));
					isAddCharacters = false;
				}
				if (isWordSeparator) {
					wordStartPos.add(pos);
					wordTexts.add(chr);
				}
			} else {
				if (isAddCharacters == false) {
					startPos = pos;
					isAddCharacters = true;
				}
			}
			pos++;
		}
		if (isAddCharacters) {
			wordStartPos.add(startPos);
			wordTexts.add(input.substring(startPos, pos));
		}

		int numWordsToParse = Math.min(maxWordsToParse, wordTexts.size());
		this.zm.setByte(parseAddr + 1, numWordsToParse);

		for (int i = 0; i < numWordsToParse; i++) {
			String word = wordTexts.get(i);

			int wordAddr = this.zm.getWordAddr(word);
			int wordLen = word.length();
			int wordPos = wordStartPos.get(i) + 1; // offset to text-buffer

			int wordOffset = parseAddr + 1 + 1 + (i * 4);
			this.zm.setWord(wordOffset, wordAddr);
			this.zm.setByte(wordOffset + 2, wordLen);
			this.zm.setByte(wordOffset + 3, wordPos);
		}
	}

	private void Z_print_char(int args[]) {
		int value = args[0];
		if (value == 0x0D) {
			print(ZMachine.EOL);
		} else if ((value >= 0x20) && (value <= 0x7E)) {
			print(String.format("%c", value));
		}
	}

	private void Z_print_num(int args[]) {
		int value = this.zm.toInt32(args[0]);
		print(String.format("%d", value));
	}

	private void Z_random(int args[]) { // STORE OP
		int arg = this.zm.toInt32(args[0]);
		int value = this.zm.random(arg);
		this.zm.consumeAndStore(value);
	}

	private void Z_push(int args[]) {
		int value = args[0];
		this.zm.stack.push(value);
	}

	private void Z_pull(int args[]) {
		int varNumber = args[0];
		int value = this.zm.stack.pop();
		this.zm.setVariableValue(varNumber, value);
	}

	private void Z_split_window(int args[]) {
		// ignore
	}

	private void Z_set_window(int args[]) {
		// ignore
	}

	private void Z_output_stream(int args[]) {
		// ignore
	}

	private void Z_input_stream(int args[]) {
		// ignore
	}

	private void Z_sound_effect(int args[]) {
		// ignore
	}

	private void call0Op(int opCodeNr) {
		switch (opCodeNr) {
			case 0x00:
				Z_rtrue();
				break;
			case 0x01:
				Z_rfalse();
				break;
			case 0x02:
				Z_print();
				break;
			case 0x03:
				Z_print_ret();
				break;
			case 0x04:
				Z_nop();
				break;
			case 0x05:
				Z_save();
				break;
			case 0x06:
				Z_restore();
				break;
			case 0x07:
				Z_restart();
				break;
			case 0x08:
				Z_ret_popped();
				break;
			case 0x09:
				Z_pop();
				break;
			case 0x0A:
				Z_quit();
				break;
			case 0x0B:
				Z_new_line();
				break;
			case 0x0C:
				Z_show_status();
				break;
			case 0x0D:
				Z_verify();
				break;
			default:
				halt(String.format("Illegal opcode number 0OP:0x%x", opCodeNr));
				break;
		}
	}

	private void call1Op(int opCodeNr, int arg) {
		switch (opCodeNr) {
			case 0x00:
				Z_jz(arg);
				break;
			case 0x01:
				Z_get_sibling(arg);
				break;
			case 0x02:
				Z_get_child(arg);
				break;
			case 0x03:
				Z_get_parent(arg);
				break;
			case 0x04:
				Z_get_prop_len(arg);
				break;
			case 0x05:
				Z_inc(arg);
				break;
			case 0x06:
				Z_dec(arg);
				break;
			case 0x07:
				Z_print_addr(arg);
				break;
			case 0x09:
				Z_remove_obj(arg);
				break;
			case 0x0A:
				Z_print_obj(arg);
				break;
			case 0x0B:
				Z_ret(arg);
				break;
			case 0x0C:
				Z_jump(arg);
				break;
			case 0x0D:
				Z_print_paddr(arg);
				break;
			case 0x0E:
				Z_load(arg);
				break;
			case 0x0F:
				Z_not(arg);
				break;
			default:
				halt(String.format("Illegal opcode number 1OP:0x%x", opCodeNr));
				break;
		}
	}

	private void call2Op(int opCodeNr, int args[]) {
		switch (opCodeNr) {
			case 0x01:
				Z_je(args);
				break;
			case 0x02:
				Z_jl(args);
				break;
			case 0x03:
				Z_jg(args);
				break;
			case 0x04:
				Z_dec_chk(args);
				break;
			case 0x05:
				Z_inc_chk(args);
				break;
			case 0x06:
				Z_jin(args);
				break;
			case 0x07:
				Z_test(args);
				break;
			case 0x08:
				Z_or(args);
				break;
			case 0x09:
				Z_and(args);
				break;
			case 0x0A:
				Z_test_attr(args);
				break;
			case 0x0B:
				Z_set_attr(args);
				break;
			case 0x0C:
				Z_clear_attr(args);
				break;
			case 0x0D:
				Z_store(args);
				break;
			case 0x0E:
				Z_insert_obj(args);
				break;
			case 0x0F:
				Z_loadw(args);
				break;
			case 0x10:
				Z_loadb(args);
				break;
			case 0x11:
				Z_get_prop(args);
				break;
			case 0x12:
				Z_get_prop_addr(args);
				break;
			case 0x13:
				Z_get_next_prop(args);
				break;
			case 0x14:
				Z_add(args);
				break;
			case 0x15:
				Z_sub(args);
				break;
			case 0x16:
				Z_mul(args);
				break;
			case 0x17:
				Z_div(args);
				break;
			case 0x18:
				Z_mod(args);
				break;
			default:
				halt(String.format("Illegal opcode number 2OP:0x%x", opCodeNr));
				break;
		}
	}

	private void callVarOp(int opCodeNr, int args[]) {
		switch (opCodeNr) {
			case 0x00:
				Z_call(args);
				break;
			case 0x01:
				Z_storew(args);
				break;
			case 0x02:
				Z_storeb(args);
				break;
			case 0x03:
				Z_put_prop(args);
				break;
			case 0x04:
				Z_sread(args);
				break;
			case 0x05:
				Z_print_char(args);
				break;
			case 0x06:
				Z_print_num(args);
				break;
			case 0x07:
				Z_random(args);
				break;
			case 0x08:
				Z_push(args);
				break;
			case 0x09:
				Z_pull(args);
				break;
			case 0x0A:
				Z_split_window(args);
				break;
			case 0x0B:
				Z_set_window(args);
				break;
			case 0x13:
				Z_output_stream(args);
				break;
			case 0x14:
				Z_input_stream(args);
				break;
			case 0x15:
				Z_sound_effect(args);
				break;
			default:
				halt(String.format("Illegal opcode number VAR:0x%x", opCodeNr));
				break;
		}
	}

	private void interpretShortForm() {
		int opCode = this.zm.consumeByte();

		int opType = (opCode >> 4) & 0b11;
		int opCodeNr = opCode & 0b1111;

		if (opType == ZMachine.OPERAND_OMITTED) {
			call0Op(opCodeNr);
		} else {
			int opValue = this.zm.consumeOperand(opType);
			call1Op(opCodeNr, opValue);
		}
	}

	private void interpretVarForm() {
		int opCode = this.zm.consumeByte();

		int opCodeNr = opCode & 0b1_1111;
		int opTypes = this.zm.consumeByte();

		int[] args = this.zm.consumeOperands(opTypes);
		boolean is2OP = this.zm.isBitClear(opCode, 5);
		if (is2OP) {
			call2Op(opCodeNr, args);
		} else {
			callVarOp(opCodeNr, args);
		}
	}

	private void interpretLongForm() {
		int opCode = this.zm.consumeByte();

		int opType1 = this.zm.isBitClear(opCode, 6) ? ZMachine.OPERAND_SMALL : ZMachine.OPERAND_VARIABLE;
		int opType2 = this.zm.isBitClear(opCode, 5) ? ZMachine.OPERAND_SMALL : ZMachine.OPERAND_VARIABLE;
		int opCodeNr = opCode & 0b1_1111;

		int opValue1 = this.zm.consumeOperand(opType1);
		int opValue2 = this.zm.consumeOperand(opType2);
		call2Op(opCodeNr, new int[] { opValue1, opValue2 });
	}

	private void interpretInstruction() {
		int opCode = this.zm.peekByte();

		int form = (opCode >> 6) & 0b11;
		switch (form) {
			case 0b10:
				interpretShortForm();
				break;
			case 0b11:
				interpretVarForm();
				break;
			default:
				interpretLongForm();
				break;
		}
	}

	private void run(ZMachine zm) {
		if (zm.header.versionNumber != 3) {
			System.out.println(ERROR_NOT_VERSION_3);
			return;
		}

		this.zm = zm;
		while (zm.isRunning) {
			interpretInstruction();
		}
	}

	private void halt(String errorMessage) {
		throw new RuntimeException("Z-Interpreter halted: " + errorMessage);
	}

	private static final String CR = System.getProperty("line.separator"); // platform-dependent EOL

	private static final String BANNER = "" + //
			" ____      ___     _                        _           " + CR + //
			"|_  / ___ |_ _|_ _| |_ ___ _ _ _ __ _ _ ___| |_ ___ _ _ " + CR + //
			" / / |___| | || ' \\  _/ -_) '_| '_ \\ '_/ -_)  _/ -_) '_|" + CR + //
			"/___|     |___|_||_\\__\\___|_| | .__/_| \\___|\\__\\___|_|  " + CR + //
			"                              |_|                       " + CR + //
			"Version 1.3 (13-MAR-2021) (C) by Lorenz Wiest" + CR;

	private static final String HELP = "" + //
			"Usage: java ZInterpreter [<options>] <story-file>" + CR + //
			"Options: -showScoreUpdates | Prints the score whenever it changes.";

	private static final String ERROR_NOT_VERSION_3 = //
			"ERROR: ZInterpreter supports version 3 stories only.";

	private static final String ERROR_FILE_NOT_FOUND = //
			"ERROR: Story file \"%s\" not found.";

	public static void main(String[] args) {
		System.out.println(BANNER);

		boolean isShowScoreUpdates = false;
		String storyFilename = null;
		boolean isArgsOk = false;

		if (args.length == 1) {
			storyFilename = args[0];
			isArgsOk = true;
		} else if (args.length == 2) {
			if (args[0].equals("-showScoreUpdates")) {
				isShowScoreUpdates = true;
				storyFilename = args[1];
				isArgsOk = true;
			}
		}
		if (isArgsOk == false) {
			System.out.println(HELP);
			return;
		}

		File storyFile = new File(storyFilename);
		if (storyFile.exists() == false) {
			System.out.println(String.format(ERROR_FILE_NOT_FOUND, storyFilename));
			return;
		}

		try {
			Path storyFilePath = storyFile.toPath();
			byte[] story = Files.readAllBytes(storyFilePath);
			ZMachine zm = new ZMachine(story);
			new ZInterpreter(storyFilePath, isShowScoreUpdates).run(zm);
		} catch (IOException e) {
			// ignore
		}
	}
}

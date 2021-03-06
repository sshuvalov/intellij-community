// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.ui.tree.render;

import com.intellij.debugger.JavaDebuggerBundle;
import com.intellij.debugger.engine.*;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.EvaluationContext;
import com.intellij.debugger.engine.jdi.ThreadReferenceProxy;
import com.intellij.debugger.engine.managerThread.SuspendContextCommand;
import com.intellij.debugger.impl.DebuggerUtilsEx;
import com.intellij.debugger.impl.DebuggerUtilsImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.rt.debugger.BatchEvaluatorServer;
import com.intellij.util.io.Bits;
import com.sun.jdi.*;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class BatchEvaluator {
  private static final Logger LOG = Logger.getInstance(BatchEvaluator.class);

  private final DebugProcess myDebugProcess;
  private boolean myBatchEvaluatorChecked;
  private ClassType myBatchEvaluatorClass;
  private Method myBatchEvaluatorMethod;

  private static final Key<BatchEvaluator> BATCH_EVALUATOR_KEY = new Key<>("BatchEvaluator");
  public static final Key<Boolean> REMOTE_SESSION_KEY = new Key<>("is_remote_session_key");

  private final HashMap<SuspendContext, List<ToStringCommand>> myBuffer = new HashMap<>();

  private BatchEvaluator(DebugProcess process) {
    myDebugProcess = process;
    myDebugProcess.addDebugProcessListener(new DebugProcessListener() {
      @Override
      public void processDetached(@NotNull DebugProcess process, boolean closedByUser) {
        myBatchEvaluatorChecked = false;
        myBatchEvaluatorClass = null;
        myBatchEvaluatorMethod = null;
      }
    });
  }

  public boolean hasBatchEvaluator(EvaluationContext evaluationContext) {
    if (!myBatchEvaluatorChecked) {
      myBatchEvaluatorChecked = true;
      if (DebuggerUtilsImpl.isRemote(myDebugProcess)) {
        // optimization: for remote sessions the BatchEvaluator is not there for sure
        return false;
      }

      ThreadReferenceProxy thread = evaluationContext.getSuspendContext().getThread();

      if (thread == null) {
        return false;
      }

      ThreadReference threadReference = thread.getThreadReference();
      if(threadReference == null) {
        return false;
      }

      try {
        myBatchEvaluatorClass = (ClassType)myDebugProcess.findClass(evaluationContext, BatchEvaluatorServer.class.getName(),
          evaluationContext.getClassLoader());
      }
      catch (EvaluateException ignored) {
      }

      if (myBatchEvaluatorClass != null) {
          myBatchEvaluatorMethod = DebuggerUtils.findMethod(myBatchEvaluatorClass, "evaluate", "([Ljava/lang/Object;)Ljava/lang/String;");
      }
    }
    return myBatchEvaluatorMethod != null;
  }

  public void invoke(ToStringCommand command) {
    DebuggerManagerThreadImpl.assertIsManagerThread();

    final EvaluationContext evaluationContext = command.getEvaluationContext();
    final SuspendContext suspendContext = evaluationContext.getSuspendContext();

    if(!Registry.is("debugger.batch.evaluation") || !hasBatchEvaluator(evaluationContext)) {
      myDebugProcess.getManagerThread().invokeCommand(command);
    }
    else {
      List<ToStringCommand> toStringCommands = myBuffer.get(suspendContext);
      if(toStringCommands == null) {
        final List<ToStringCommand> commands = new ArrayList<>();
        toStringCommands = commands;
        myBuffer.put(suspendContext, commands);

        myDebugProcess.getManagerThread().invokeCommand(new SuspendContextCommand() {
          @Override
          public SuspendContext getSuspendContext() {
            return suspendContext;
          }

          @Override
          public void action() {
            myBuffer.remove(suspendContext);

            if(!doEvaluateBatch(commands, evaluationContext)) {
              commands.forEach(ToStringCommand::action);
            }
          }

          @Override
          public void commandCancelled() {
            myBuffer.remove(suspendContext);
          }
        });
      }

      toStringCommands.add(command);
    }
  }

  public static BatchEvaluator getBatchEvaluator(DebugProcess debugProcess) {
    BatchEvaluator batchEvaluator = debugProcess.getUserData(BATCH_EVALUATOR_KEY);

    if(batchEvaluator == null) {
      batchEvaluator = new BatchEvaluator(debugProcess);
      debugProcess.putUserData(BATCH_EVALUATOR_KEY, batchEvaluator);
    }
    return batchEvaluator;
  }

  private boolean doEvaluateBatch(List<ToStringCommand> requests, EvaluationContext evaluationContext) {
    try {
      DebugProcess debugProcess = evaluationContext.getDebugProcess();
      List<Value> values = StreamEx.of(requests).map(ToStringCommand::getValue).toList();

      ArrayType objectArrayClass = (ArrayType)debugProcess.findClass(
        evaluationContext,
        "java.lang.Object[]",
        evaluationContext.getClassLoader());
      if (objectArrayClass == null) {
        return false;
      }

      ArrayReference argArray = DebuggerUtilsEx.mirrorOfArray(objectArrayClass, values.size(), evaluationContext);
      argArray.setValues(values);
      String value = DebuggerUtils.processCollectibleValue(
        () -> debugProcess.invokeMethod(evaluationContext, myBatchEvaluatorClass, myBatchEvaluatorMethod, Collections.singletonList(argArray)),
        result -> result instanceof StringReference ? ((StringReference)result).value() : null
      );
      if (value != null) {
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        int pos = 0;
        Iterator<ToStringCommand> iterator = requests.iterator();
        while (pos < bytes.length) {
          int length = Bits.getInt(bytes, pos);
          boolean error = length < 0;
          length = Math.abs(length);
          String message = new String(bytes, pos + 4, length, StandardCharsets.ISO_8859_1);
          if (!iterator.hasNext()) {
            return false;
          }
          ToStringCommand command = iterator.next();
          if (error) {
            command.evaluationError(JavaDebuggerBundle.message("evaluation.error.method.exception", message));
          }
          else {
            command.evaluationResult(message);
          }
          pos += length + 4;
        }
      }
      return true;
    }
    catch (ClassNotLoadedException | ObjectCollectedException | EvaluateException | InvalidTypeException e) {
      LOG.debug(e);
    }
    return false;
  }


}

package com.sun.jna;

import com.github.unidbg.*;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.arm.backend.dynarmic.DynarmicLoader;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.hook.HookContext;
import com.github.unidbg.hook.ReplaceCallback;
import com.github.unidbg.hook.hookzz.HookEntryInfo;
import com.github.unidbg.hook.hookzz.HookZz;
import com.github.unidbg.hook.hookzz.IHookZz;
import com.github.unidbg.hook.hookzz.InstrumentCallback;
import com.github.unidbg.hook.whale.IWhale;
import com.github.unidbg.hook.whale.Whale;
import com.github.unidbg.hook.xhook.IxHook;
import com.github.unidbg.linux.android.AndroidARMEmulator;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.XHookImpl;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.memory.MemoryBlock;
import com.github.unidbg.pointer.UnidbgPointer;
import com.github.unidbg.utils.Inspector;

import java.io.File;
import java.io.IOException;

public class JniDispatch32 extends AbstractJni {

    private static LibraryResolver createLibraryResolver() {
        return new AndroidResolver(23);
    }

    private static AndroidEmulator createARMEmulator() {
        return new AndroidARMEmulator("com.sun.jna");
    }

    private final AndroidEmulator emulator;
    private final Module module;

    private final DvmClass cNative;

    static {
        DynarmicLoader.useDynarmic();
    }

    private JniDispatch32() {
        emulator = createARMEmulator();
        final Memory memory = emulator.getMemory();
        memory.setLibraryResolver(createLibraryResolver());

        VM vm = emulator.createDalvikVM(null);
        vm.setJni(this);
        vm.setVerbose(true);
        DalvikModule dm = vm.loadLibrary(new File("unidbg-android/src/test/resources/example_binaries/armeabi-v7a/libjnidispatch.so"), false);
        dm.callJNI_OnLoad(emulator);
        module = dm.getModule();

        cNative = vm.resolveClass("com/sun/jna/Native");

        Symbol __system_property_get = module.findSymbolByName("__system_property_get", true);
        MemoryBlock block = memory.malloc(0x10);
        Number ret = __system_property_get.call(emulator, "ro.build.version.sdk", block.getPointer())[0];
        System.out.println("sdk=" + new String(block.getPointer().getByteArray(0, ret.intValue())) + ", libc=" + memory.findModule("libc.so"));
    }

    private void destroy() throws IOException {
        emulator.close();
        System.out.println("destroy");
    }

    public static void main(String[] args) throws Exception {
        JniDispatch32 test = new JniDispatch32();

        test.test();

        test.destroy();
    }

    private void test() {
        IxHook xHook = XHookImpl.getInstance(emulator);
        xHook.register("libjnidispatch.so", "malloc", new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, HookContext context, long originFunction) {
                int size = context.getIntArg(0);
                context.push(size);
                System.out.println("malloc=" + size);
                return HookStatus.RET(emulator, originFunction);
            }
            @Override
            public void postCall(Emulator<?> emulator, HookContext context) {
                int size = context.pop();
                System.out.println("malloc=" + size + ", ret=" + context.getPointerArg(0));
            }
        }, true);
        xHook.refresh();

        IWhale whale = Whale.getInstance(emulator);
        Symbol free = emulator.getMemory().findModule("libc.so").findSymbolByName("free");
        whale.inlineHookFunction(free, new ReplaceCallback() {
            @Override
            public HookStatus onCall(Emulator<?> emulator, long originFunction) {
                System.out.println("WInlineHookFunction free=" + emulator.getContext().getPointerArg(0));
                return HookStatus.RET(emulator, originFunction);
            }
        });

        long start = System.currentTimeMillis();
        final int size = 0x20;
        Number ret = cNative.callStaticJniMethodLong(emulator, "malloc(J)J", size);
        Pointer pointer = UnidbgPointer.pointer(emulator, ret.intValue() & 0xffffffffL);
        assert pointer != null;
        pointer.setString(0, getClass().getName());
        Inspector.inspect(pointer.getByteArray(0, size), "malloc ret=" + ret + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        IHookZz hookZz = HookZz.getInstance(emulator);
        Symbol newJavaString = module.findSymbolByName("newJavaString");
        hookZz.instrument(newJavaString, new InstrumentCallback<RegisterContext>() {
            @Override
            public void dbiCall(Emulator<?> emulator, RegisterContext ctx, HookEntryInfo info) {
                Pointer value = ctx.getPointerArg(1);
                Pointer encoding = ctx.getPointerArg(2);
                System.out.println("newJavaString value=" + value.getString(0) + ", encoding=" + encoding.getString(0));
            }
        });

        StringObject version = cNative.callStaticJniMethodObject(emulator, "getNativeVersion()Ljava/lang/String;");
        System.out.println("getNativeVersion version=" + version.getValue() + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        StringObject checksum = cNative.callStaticJniMethodObject(emulator, "getAPIChecksum()Ljava/lang/String;");
        System.out.println("getAPIChecksum checksum=" + checksum.getValue() + ", offset=" + (System.currentTimeMillis() - start) + "ms");

        ret = cNative.callStaticJniMethodInt(emulator, "sizeof(I)I", 0);
        System.out.println("sizeof POINTER_SIZE=" + ret.intValue() + ", offset=" + (System.currentTimeMillis() - start) + "ms");
    }

    @Override
    public DvmObject<?> callStaticObjectMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        if ("java/lang/System->getProperty(Ljava/lang/String;)Ljava/lang/String;".equals(signature)) {
            StringObject string = varArg.getObject(0);
            return new StringObject(vm, System.getProperty(string.getValue()));
        }

        return super.callStaticObjectMethod(vm, dvmClass, signature, varArg);
    }

}

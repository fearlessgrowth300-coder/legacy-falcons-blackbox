package top.niunaijun.blackbox.utils.compat;

import java.lang.reflect.Method;
import java.util.List;

import black.android.content.pm.BRParceledListSlice;

public class ParceledListSliceCompat {

	public static boolean isReturnParceledListSlice(Method method) {
		return method != null && method.getReturnType() == BRParceledListSlice.getRealClass();
	}

	public static boolean isParceledListSlice(Object obj) {
		return obj != null && obj.getClass() == BRParceledListSlice.getRealClass();
	}

	/**
	 * Android 17 declares typed subclasses (e.g. PackageInfoList) as some return types.
	 * A plain ParceledListSlice then fails the proxy's cast, so build the declared type.
	 */
	public static Object create(Method method, List<?> list) {
		Class<?> declared = method == null ? null : method.getReturnType();
		Class<?> base = BRParceledListSlice.getRealClass();
		if (declared != null && base != null && declared != base && base.isAssignableFrom(declared)) {
			try {
				return declared.getConstructor(List.class).newInstance(list);
			} catch (Throwable ignored) {
			}
		}
		return create(list);
	}

	public static Object create(List<?> list) {
		Object slice = BRParceledListSlice.get()._new(list);
		if (slice != null) {
			return slice;
		} else {
			slice = BRParceledListSlice.get()._new();
		}
		for (Object item : list) {
			BRParceledListSlice.get(slice).append(item);
		}
		BRParceledListSlice.get(slice).setLastSlice(true);
		return slice;
	}
}

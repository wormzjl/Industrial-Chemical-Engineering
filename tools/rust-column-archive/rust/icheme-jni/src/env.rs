//! The few `JNIEnv` functions the exports use, through `jni-sys`'s function table: array lengths, region copies
//! (no critical arrays: a solve may run for seconds and would hold the garbage collector), the exception check after
//! each copy, and one string.

use std::ffi::CStr;

use jni_sys::{JNI_TRUE, JNIEnv, jarray, jdoubleArray, jintArray, jsize, jstring};

use crate::layout::Status;

/// The calling thread's `JNIEnv`, for the duration of one native call.
pub struct Env(*mut JNIEnv);

impl Env {
    /// # Safety
    /// `env` is the `JNIEnv` the JVM passed to the native method now running on this thread.
    pub unsafe fn new(env: *mut JNIEnv) -> Option<Self> {
        // SAFETY: a non-null env from the JVM points to its function table pointer.
        if env.is_null() || unsafe { (*env).is_null() } { None } else { Some(Self(env)) }
    }

    fn table(&self) -> &jni_sys::JNINativeInterface_ {
        // SAFETY: checked non-null in `new`; the JVM keeps the table alive for the call.
        unsafe { &**self.0 }
    }

    /// The length of a Java array; a null reference or a missing table entry is a layout error.
    ///
    /// # Safety
    /// `array` is null or a valid local reference to a Java array passed to this call.
    pub unsafe fn array_length(&self, array: jarray) -> Result<usize, Status> {
        if array.is_null() {
            return Err(Status::BadLayout);
        }
        let f = self.table().GetArrayLength.ok_or(Status::BadLayout)?;
        // SAFETY: a valid env and a non-null array reference (the caller's contract).
        let n = unsafe { f(self.0, array) };
        self.check_exception()?;
        usize::try_from(n).map_err(|_| Status::BadLayout)
    }

    /// Copies `buffer.len()` doubles from the start of `array` (`GetDoubleArrayRegion`).
    ///
    /// # Safety
    /// `array` is a valid local reference to a `double[]` of at least `buffer.len()` elements.
    pub unsafe fn get_doubles(&self, array: jdoubleArray, buffer: &mut [f64]) -> Result<(), Status> {
        let f = self.table().GetDoubleArrayRegion.ok_or(Status::BadLayout)?;
        let n = jsize::try_from(buffer.len()).map_err(|_| Status::BadLayout)?;
        // SAFETY: the buffer holds n writable doubles; the array has at least n (the caller's contract).
        unsafe { f(self.0, array, 0, n, buffer.as_mut_ptr()) };
        self.check_exception()
    }

    /// Writes `values` to the start of `array` (`SetDoubleArrayRegion`).
    ///
    /// # Safety
    /// `array` is a valid local reference to a `double[]` of at least `values.len()` elements.
    pub unsafe fn set_doubles(&self, array: jdoubleArray, values: &[f64]) -> Result<(), Status> {
        let f = self.table().SetDoubleArrayRegion.ok_or(Status::BadLayout)?;
        let n = jsize::try_from(values.len()).map_err(|_| Status::BadLayout)?;
        // SAFETY: values holds n readable doubles; the array has at least n (the caller's contract).
        unsafe { f(self.0, array, 0, n, values.as_ptr()) };
        self.check_exception()
    }

    /// Writes `values` to the start of `array` (`SetIntArrayRegion`).
    ///
    /// # Safety
    /// `array` is a valid local reference to an `int[]` of at least `values.len()` elements.
    pub unsafe fn set_ints(&self, array: jintArray, values: &[i32]) -> Result<(), Status> {
        let f = self.table().SetIntArrayRegion.ok_or(Status::BadLayout)?;
        let n = jsize::try_from(values.len()).map_err(|_| Status::BadLayout)?;
        // SAFETY: values holds n readable ints; the array has at least n (the caller's contract).
        unsafe { f(self.0, array, 0, n, values.as_ptr()) };
        self.check_exception()
    }

    /// A new Java string from modified UTF-8 (`NewStringUTF`); null if the JVM could not make it.
    pub fn new_string(&self, text: &CStr) -> jstring {
        match self.table().NewStringUTF {
            // SAFETY: a valid env and a NUL-terminated string that outlives the call.
            Some(f) => unsafe { f(self.0, text.as_ptr()) },
            None => std::ptr::null_mut(),
        }
    }

    /// A pending Java exception (an out-of-bounds region, for instance) is cleared and reported as a layout error,
    /// so Java runs the request on its own path instead of meeting the exception (pitfall 11 of the plan).
    fn check_exception(&self) -> Result<(), Status> {
        let check = self.table().ExceptionCheck.ok_or(Status::BadLayout)?;
        // SAFETY: a valid env; ExceptionCheck may be called with an exception pending.
        if unsafe { check(self.0) } == JNI_TRUE {
            if let Some(clear) = self.table().ExceptionClear {
                // SAFETY: a valid env with an exception pending.
                unsafe { clear(self.0) };
            }
            return Err(Status::BadLayout);
        }
        Ok(())
    }
}

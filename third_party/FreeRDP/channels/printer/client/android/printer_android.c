/**
 * cRDP / FreeRDP — Android printer backend driver.
 *
 * Plugged into channels/printer/client/printer_main.c via
 *   freerdp_load_channel_addin_entry("printer", "android", ...).
 *
 * Each print job opens a file under the spool directory configured by the
 * Kotlin host (PrinterRedirectBridge.setSpoolDir). Bytes streamed by the
 * remote print queue land in that file verbatim — when the remote driver is
 * the standard "Microsoft XPS Document Writer" (the Easy Print default) the
 * payload is an XPS package. We don't parse it: a file watcher on the Kotlin
 * side picks the closed file up and hands it to whatever consumer the user
 * configured (notification → SAF share → Android PrintManager / external
 * viewer).
 *
 * No device enumeration: there is exactly one printer announced per session,
 * matching how a phone presents itself to the user (a single virtual sink).
 */

#include <freerdp/config.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>

#include <winpr/assert.h>
#include <winpr/crt.h>
#include <winpr/file.h>
#include <winpr/path.h>
#include <winpr/string.h>
#include <winpr/synch.h>

#include <freerdp/channels/rdpdr.h>
#include <freerdp/client/printer.h>
#include <freerdp/channels/log.h>

#include "printer_android.h"

#define TAG CHANNELS_TAG("printer.client.android")

#define DEFAULT_PRINTER_NAME "cRDP"
/* Matches FreeRDP's CUPS backend default. Windows treats this string as an
 * Easy Print trigger: no driver match exists locally, so the server falls back
 * to the in-box "Microsoft Remote Desktop Easy Print" driver and renders the
 * job as XPS — exactly what we need to spool back to Android. Picking a real
 * driver name (e.g. "Microsoft XPS Document Writer v4") instead causes the
 * server to try literal driver-name matching and silently drop the install if
 * the exact string isn't in its driver list. */
#define DEFAULT_DRIVER_NAME "MS Publisher Imagesetter"

typedef struct
{
	rdpPrinterDriver driver;
	size_t id_sequence;
	size_t references;
} rdpAndroidPrinterDriver;

typedef struct
{
	rdpPrintJob printjob;
	FILE* fp;
	char* path;
} rdpAndroidPrintJob;

typedef struct
{
	rdpPrinter printer;
	rdpAndroidPrintJob* printjob;
} rdpAndroidPrinter;

/* The driver is a process-global singleton (FreeRDP loads channel addins once
 * per process); the spool dir and printer-name overrides come in via JNI from
 * Kotlin and are read by every job. Guard with a mutex so a concurrent JNI
 * setter can't race a CreatePrintJob that's snapshotting them. */
static CRITICAL_SECTION g_config_lock;
static BOOL g_config_lock_init = FALSE;
static char* g_spool_dir = NULL;
static char* g_printer_name = NULL;
static rdpAndroidPrinterDriver* g_uniq_driver = NULL;

static void ensure_config_lock(void)
{
	if (!g_config_lock_init)
	{
		InitializeCriticalSection(&g_config_lock);
		g_config_lock_init = TRUE;
	}
}

static char* dup_str_locked(const char* s)
{
	if (!s)
		return NULL;
	return _strdup(s);
}

UINT printer_android_set_spool_dir(const char* path)
{
	ensure_config_lock();
	EnterCriticalSection(&g_config_lock);
	free(g_spool_dir);
	g_spool_dir = dup_str_locked(path);
	LeaveCriticalSection(&g_config_lock);
	return CHANNEL_RC_OK;
}

UINT printer_android_set_printer_name(const char* name)
{
	ensure_config_lock();
	EnterCriticalSection(&g_config_lock);
	free(g_printer_name);
	g_printer_name = dup_str_locked(name);
	LeaveCriticalSection(&g_config_lock);
	return CHANNEL_RC_OK;
}

static char* snapshot_spool_dir(void)
{
	ensure_config_lock();
	EnterCriticalSection(&g_config_lock);
	char* copy = dup_str_locked(g_spool_dir);
	LeaveCriticalSection(&g_config_lock);
	return copy;
}

static char* snapshot_printer_name(void)
{
	ensure_config_lock();
	EnterCriticalSection(&g_config_lock);
	char* copy = dup_str_locked(g_printer_name);
	LeaveCriticalSection(&g_config_lock);
	return copy;
}

/* Compose <spool>/<jobid>-<YYYYMMDD-HHMMSS>.xps. The .xps extension is a hint
 * to whatever consumer the Kotlin side wires up; the data itself may be any
 * Print Spool format depending on the server-side print queue config. */
static char* build_spool_path(UINT32 job_id)
{
	char* dir = snapshot_spool_dir();
	if (!dir || dir[0] == '\0')
	{
		free(dir);
		/* Fall back to /tmp so calls don't fail; the user just won't find them. */
		dir = _strdup("/data/local/tmp");
		if (!dir)
			return NULL;
	}

	/* Best-effort mkdir-p; ignore errors and rely on fopen to report later. */
	if (!winpr_PathFileExists(dir))
		(void)winpr_PathMakePath(dir, NULL);

	struct tm tres = { 0 };
	const time_t tt = time(NULL);
	const struct tm* t = localtime_r(&tt, &tres);

	char fname[64] = { 0 };
	(void)sprintf_s(fname, sizeof(fname) - 1, "job-%010u-%04d%02d%02d-%02d%02d%02d.xps",
	                (unsigned)job_id, t->tm_year + 1900, t->tm_mon + 1, t->tm_mday, t->tm_hour,
	                t->tm_min, t->tm_sec);

	char* combined = GetCombinedPath(dir, fname);
	free(dir);
	return combined;
}

static UINT printer_android_write_printjob(rdpPrintJob* printjob, const BYTE* data, size_t size)
{
	rdpAndroidPrintJob* job = (rdpAndroidPrintJob*)printjob;
	WINPR_ASSERT(job);
	WLog_DBG(TAG, "Write: id=%u size=%zu path=%s", (unsigned)job->printjob.id, size,
	         job->path ? job->path : "?");

	if (!job->fp || !data || size == 0)
		return CHANNEL_RC_OK;

	const size_t written = fwrite(data, 1, size, job->fp);
	if (written != size)
	{
		WLog_WARN(TAG, "short write to %s: %zu/%zu (errno=%d)", job->path ? job->path : "?",
		          written, size, errno);
		return ERROR_WRITE_FAULT;
	}
	return CHANNEL_RC_OK;
}

static void printer_android_close_printjob(rdpPrintJob* printjob)
{
	rdpAndroidPrintJob* job = (rdpAndroidPrintJob*)printjob;
	rdpAndroidPrinter* owner = NULL;
	WINPR_ASSERT(job);
	WLog_INFO(TAG, "Close: id=%u path=%s", (unsigned)job->printjob.id,
	          job->path ? job->path : "?");

	if (job->fp)
	{
		(void)fflush(job->fp);
		(void)fclose(job->fp);
		job->fp = NULL;
	}
	if (job->path)
	{
		WLog_INFO(TAG, "spooled print job %u to %s", (unsigned)job->printjob.id, job->path);
	}

	owner = (rdpAndroidPrinter*)job->printjob.printer;
	if (owner)
		owner->printjob = NULL;

	free(job->path);
	free(job);
}

static rdpPrintJob* printer_android_create_printjob(rdpPrinter* printer, UINT32 id)
{
	rdpAndroidPrinter* owner = (rdpAndroidPrinter*)printer;
	WINPR_ASSERT(owner);
	WLog_INFO(TAG, "CreatePrintJob: printer='%s' id=%u", printer->name, (unsigned)id);

	if (owner->printjob != NULL)
	{
		WLog_WARN(TAG, "printjob already exists on printer '%s', aborting", printer->name);
		return NULL;
	}

	rdpAndroidPrintJob* job = (rdpAndroidPrintJob*)calloc(1, sizeof(rdpAndroidPrintJob));
	if (!job)
		return NULL;

	job->printjob.id = id;
	job->printjob.printer = printer;
	job->printjob.Write = printer_android_write_printjob;
	job->printjob.Close = printer_android_close_printjob;

	job->path = build_spool_path(id);
	if (!job->path)
	{
		WLog_ERR(TAG, "build_spool_path failed");
		free(job);
		return NULL;
	}

	job->fp = winpr_fopen(job->path, "wb");
	if (!job->fp)
	{
		WLog_ERR(TAG, "fopen('%s') failed: errno=%d", job->path, errno);
		free(job->path);
		free(job);
		return NULL;
	}

	owner->printjob = job;
	return &job->printjob;
}

static rdpPrintJob* printer_android_find_printjob(rdpPrinter* printer, UINT32 id)
{
	rdpAndroidPrinter* owner = (rdpAndroidPrinter*)printer;
	WINPR_ASSERT(owner);

	if (!owner->printjob)
		return NULL;
	if (owner->printjob->printjob.id != id)
		return NULL;
	return &owner->printjob->printjob;
}

static void printer_android_free_printer(rdpPrinter* printer)
{
	rdpAndroidPrinter* a = (rdpAndroidPrinter*)printer;
	WINPR_ASSERT(a);

	if (a->printjob)
	{
		WINPR_ASSERT(a->printjob->printjob.Close);
		a->printjob->printjob.Close(&a->printjob->printjob);
	}
	if (printer->backend)
	{
		WINPR_ASSERT(printer->backend->ReleaseRef);
		printer->backend->ReleaseRef(printer->backend);
	}
	free(printer->name);
	free(printer->driver);
	free(printer);
}

static void printer_android_add_ref_printer(rdpPrinter* printer)
{
	if (printer)
		printer->references++;
}

static void printer_android_release_ref_printer(rdpPrinter* printer)
{
	if (!printer)
		return;
	if (printer->references <= 1)
		printer_android_free_printer(printer);
	else
		printer->references--;
}

static rdpPrinter* printer_android_new_printer(rdpAndroidPrinterDriver* drv, const char* name,
                                               const char* driverName, BOOL is_default)
{
	rdpAndroidPrinter* a = (rdpAndroidPrinter*)calloc(1, sizeof(rdpAndroidPrinter));
	if (!a)
		return NULL;

	a->printer.backend = &drv->driver;
	a->printer.id = drv->id_sequence++;
	a->printer.name = _strdup(name ? name : DEFAULT_PRINTER_NAME);
	if (!a->printer.name)
		goto fail;

	a->printer.driver = _strdup(driverName ? driverName : DEFAULT_DRIVER_NAME);
	if (!a->printer.driver)
		goto fail;

	a->printer.is_default = is_default;
	a->printer.CreatePrintJob = printer_android_create_printjob;
	a->printer.FindPrintJob = printer_android_find_printjob;
	a->printer.AddRef = printer_android_add_ref_printer;
	a->printer.ReleaseRef = printer_android_release_ref_printer;

	a->printer.AddRef(&a->printer);
	WINPR_ASSERT(a->printer.backend->AddRef);
	a->printer.backend->AddRef(a->printer.backend);

	return &a->printer;

fail:
	printer_android_free_printer(&a->printer);
	return NULL;
}

static void printer_android_release_enum_printers(rdpPrinter** printers)
{
	rdpPrinter** cur = printers;
	while (cur && *cur)
	{
		if ((*cur)->ReleaseRef)
			(*cur)->ReleaseRef(*cur);
		cur++;
	}
	free(printers);
}

/* Single virtual printer. Name is the Kotlin-configured one or "cRDP" default;
 * driver advertises XPS so the remote side picks Easy Print. */
static rdpPrinter** printer_android_enum_printers(rdpPrinterDriver* driver)
{
	rdpAndroidPrinterDriver* drv = (rdpAndroidPrinterDriver*)driver;
	WINPR_ASSERT(drv);

	rdpPrinter** arr = (rdpPrinter**)calloc(2, sizeof(rdpPrinter*));
	if (!arr)
		return NULL;

	char* name = snapshot_printer_name();
	rdpPrinter* p =
	    printer_android_new_printer(drv, name ? name : DEFAULT_PRINTER_NAME, NULL, TRUE);
	free(name);
	if (!p)
	{
		free(arr);
		return NULL;
	}
	arr[0] = p;
	return arr;
}

static rdpPrinter* printer_android_get_printer(rdpPrinterDriver* driver, const char* name,
                                               const char* driverName, BOOL isDefault)
{
	rdpAndroidPrinterDriver* drv = (rdpAndroidPrinterDriver*)driver;
	WINPR_ASSERT(drv);
	/* If the caller didn't pin a name (typical when /printer is passed with no
	 * value), use the JNI-configured printer name. */
	char* configured = NULL;
	if (!name || name[0] == '\0')
	{
		configured = snapshot_printer_name();
		name = configured ? configured : DEFAULT_PRINTER_NAME;
	}
	rdpPrinter* p = printer_android_new_printer(drv, name, driverName, isDefault);
	free(configured);
	return p;
}

static void printer_android_add_ref_driver(rdpPrinterDriver* driver)
{
	rdpAndroidPrinterDriver* drv = (rdpAndroidPrinterDriver*)driver;
	if (drv)
		drv->references++;
}

static void printer_android_release_ref_driver(rdpPrinterDriver* driver)
{
	rdpAndroidPrinterDriver* drv = (rdpAndroidPrinterDriver*)driver;
	WINPR_ASSERT(drv);
	if (drv->references <= 1)
	{
		if (g_uniq_driver == drv)
			g_uniq_driver = NULL;
		free(drv);
	}
	else
		drv->references--;
}

FREERDP_ENTRY_POINT(UINT VCAPITYPE android_freerdp_printer_client_subsystem_entry(void* arg))
{
	rdpPrinterDriver** out = (rdpPrinterDriver**)arg;
	if (!out)
		return ERROR_INVALID_PARAMETER;

	ensure_config_lock();

	if (!g_uniq_driver)
	{
		g_uniq_driver = (rdpAndroidPrinterDriver*)calloc(1, sizeof(rdpAndroidPrinterDriver));
		if (!g_uniq_driver)
			return ERROR_OUTOFMEMORY;
		g_uniq_driver->driver.EnumPrinters = printer_android_enum_printers;
		g_uniq_driver->driver.ReleaseEnumPrinters = printer_android_release_enum_printers;
		g_uniq_driver->driver.GetPrinter = printer_android_get_printer;
		g_uniq_driver->driver.AddRef = printer_android_add_ref_driver;
		g_uniq_driver->driver.ReleaseRef = printer_android_release_ref_driver;
		g_uniq_driver->id_sequence = 1;
	}

	WINPR_ASSERT(g_uniq_driver->driver.AddRef);
	g_uniq_driver->driver.AddRef(&g_uniq_driver->driver);
	*out = &g_uniq_driver->driver;
	return CHANNEL_RC_OK;
}

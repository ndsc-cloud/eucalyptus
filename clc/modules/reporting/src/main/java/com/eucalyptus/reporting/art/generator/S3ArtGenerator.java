package com.eucalyptus.reporting.art.generator;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.log4j.Logger;

import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.reporting.art.entity.AccountArtEntity;
import com.eucalyptus.reporting.art.entity.BucketUsageArtEntity;
import com.eucalyptus.reporting.art.entity.ReportArtEntity;
import com.eucalyptus.reporting.art.entity.UserArtEntity;
import com.eucalyptus.reporting.art.util.DurationCalculator;
import com.eucalyptus.reporting.domain.ReportingAccount;
import com.eucalyptus.reporting.domain.ReportingAccountDao;
import com.eucalyptus.reporting.domain.ReportingUser;
import com.eucalyptus.reporting.domain.ReportingUserDao;
import com.eucalyptus.reporting.event_store.ReportingS3ObjectCreateEvent;
import com.eucalyptus.reporting.event_store.ReportingS3ObjectDeleteEvent;

public class S3ArtGenerator
	implements ArtGenerator
{
    private static Logger log = Logger.getLogger( S3ArtGenerator.class );

    public S3ArtGenerator()
	{
		
	}
	
	public ReportArtEntity generateReportArt(ReportArtEntity report)
	{
		log.debug("GENERATING REPORT ART");
		EntityWrapper wrapper = EntityWrapper.get( ReportingS3ObjectCreateEvent.class );

		/* NOTE: careful! This is subtler than it appears at first. A single object can
		 * be repeatedly created and deleted within the period, so we cannot just hang on
		 * to start times and end times then subtract at the end. Furthermore, an object
		 * can be created but never deleted.
		 */
		
		/* Generate a tree of zones, accounts, users, and bucket usages.
		 * Retain a Map of bucket usages at the leaf nodes.
		 * Find and retain start times and sizes for S3 objects.
		 * Set default object duration to the remaining of report (in case no
		 *   subsequent delete event is ever encountered), to be overwritten
		 *   if there is later found a delete event (or multiple delete events).
		 */
		Iterator iter = wrapper.scanWithNativeQuery( "scanS3ObjectCreateEvents" );
		Map<String,BucketUsageArtEntity> bucketUsageEntities = new HashMap<String,BucketUsageArtEntity>();
		Map<S3ObjectKey,S3ObjectData> objectData = new HashMap<S3ObjectKey,S3ObjectData>();
		DurationCalculator<S3ObjectKey> objectDurationCalculator = new DurationCalculator<S3ObjectKey>(report.getBeginMs(),report.getEndMs());
		while (iter.hasNext()) {
			ReportingS3ObjectCreateEvent createEvent = (ReportingS3ObjectCreateEvent) iter.next();
			
			ReportingUser reportingUser = ReportingUserDao.getInstance().getReportingUser(createEvent.getUserId());
			if (reportingUser==null) {
				log.error("No user corresponding to event:" + createEvent.getUserId());
			}
			ReportingAccount reportingAccount = ReportingAccountDao.getInstance().getReportingAccount(reportingUser.getAccountId());
			if (reportingAccount==null) {
				log.error("No account corresponding to user:" + reportingUser.getAccountId());
			}
			if (! report.getAccounts().containsKey(reportingAccount.getName())) {
				report.getAccounts().put(reportingAccount.getName(), new AccountArtEntity());
			}
			AccountArtEntity account = report.getAccounts().get(reportingAccount.getName());
			if (! account.getUsers().containsKey(reportingUser.getName())) {
				account.getUsers().put(reportingUser.getName(), new UserArtEntity());
			}
			UserArtEntity user = account.getUsers().get(reportingUser.getName());
			if (! user.getBucketUsage().containsKey(createEvent.getS3BucketName())) {
				user.getBucketUsage().put(createEvent.getS3BucketName(), new BucketUsageArtEntity());
			}
			BucketUsageArtEntity bucketUsage = user.getBucketUsage().get(createEvent.getS3BucketName());
			bucketUsageEntities.put(createEvent.getS3BucketName(), bucketUsage);

			S3ObjectKey objectKey = new S3ObjectKey(createEvent.getS3BucketName(),
					createEvent.getS3ObjectKey(), createEvent.getObjectVersion());
			/* A duration calculator, rather than a simple Map of start and end times,
			 * is necessary here. This is because a single object can be subsequently
			 * created and deleted within the period.
			 */
			objectDurationCalculator.addStart(objectKey, createEvent.getTimestampMs());
			/* Retain the information necessary to calculate GB-secs from object durations
			 * later. The default duration is the remaining length of the report but this
			 * will be overwritten later if we encounter a delete event (or multiple delete
			 * events) for an object before the end of the report.
			 */
			objectData.put(objectKey, new S3ObjectData(createEvent.getSizeGB()));
		}
		
		/* Find end timestamps for objects which are subsequently deleted, including
		 * objects which are created and deleted repeatedly during a single period, which
		 * is the purpose of using the duration calculator rather than just keeping a Map
		 * of start and end times. 
		 */
		iter = wrapper.scanWithNativeQuery( "scanS3ObjectDeleteEvents" );
		while (iter.hasNext()) {
			ReportingS3ObjectDeleteEvent deleteEvent = (ReportingS3ObjectDeleteEvent) iter.next();
			if (deleteEvent.getTimestampMs() < report.getEndMs()) {
				S3ObjectKey key = new S3ObjectKey(deleteEvent.getS3BucketName(), deleteEvent.getS3ObjectKey(),
					deleteEvent.getObjectVersion());
				objectDurationCalculator.addEnd(key, deleteEvent.getTimestampMs());
			}
		}
		Map<S3ObjectKey,Long> durationMap = objectDurationCalculator.getDurationMap();
		for (S3ObjectKey key: durationMap.keySet()) {
			if (objectData.containsKey(key)) {
				objectData.get(key).durationMs = durationMap.get(key);
			}
		}
		
		/* Go through all object data and update buckets
		 */
		for (S3ObjectKey objKey : objectData.keySet()) {
			S3ObjectData data = objectData.get(objKey);
			if (bucketUsageEntities.containsKey(objKey.bucketName)) {
				BucketUsageArtEntity usage = bucketUsageEntities.get(objKey.bucketName);
				usage.setObjectsNum(usage.getObjectsNum()+1);
				long gBSecs = (data.durationMs/1000)*data.sizeGB;
				usage.setGBSecs(gBSecs);
				usage.setSizeGB(usage.getSizeGB() + data.sizeGB);
			} else {
				log.error("Object without corresponding bucket:" + objKey.bucketName);
			}
		}
		
		/* Perform totals and summations for user, account, zone, and bucket
		 * todo: no zones here
		 */
		for (String accountName : report.getAccounts().keySet()) {
			AccountArtEntity account = report.getAccounts().get(accountName);
			for (String userName : account.getUsers().keySet()) {
				UserArtEntity user = account.getUsers().get(userName);
				for (String bucketName : user.getBucketUsage().keySet()) {
					BucketUsageArtEntity usage = user.getBucketUsage().get(bucketName);
					updateUsageTotals(user.getUsageTotals().getBucketTotals(), usage);
					updateUsageTotals(account.getUsageTotals().getBucketTotals(), usage);
				}
			}
		}


		return report;
	}
	
	private static void updateUsageTotals(BucketUsageArtEntity totalEntity,
			BucketUsageArtEntity newEntity)
	{

		totalEntity.setObjectsNum(totalEntity.getObjectsNum() + newEntity.getObjectsNum());
		totalEntity.setSizeGB(totalEntity.getSizeGB() + newEntity.getSizeGB());
		totalEntity.setGBSecs(totalEntity.getGBSecs() + newEntity.getGBSecs());
		totalEntity.setNumGetRequests(totalEntity.getNumGetRequests() + newEntity.getNumGetRequests());
		totalEntity.setNumPutRequests(totalEntity.getNumPutRequests() + newEntity.getNumPutRequests());

	}

	
	private class S3ObjectKey
	{
		private final String bucketName;
		private final String objectKey;
		private final String objectVer;

		private S3ObjectKey(String bucketName, String objectKey,
				String objectVer)
		{
			this.bucketName = bucketName;
			this.objectKey = objectKey;
			this.objectVer = objectVer;
		}

		@Override
		public int hashCode()
		{
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime * result
					+ ((bucketName == null) ? 0 : bucketName.hashCode());
			result = prime * result
					+ ((objectKey == null) ? 0 : objectKey.hashCode());
			result = prime * result
					+ ((objectVer == null) ? 0 : objectVer.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj)
		{
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			S3ObjectKey other = (S3ObjectKey) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (bucketName == null) {
				if (other.bucketName != null)
					return false;
			} else if (!bucketName.equals(other.bucketName))
				return false;
			if (objectKey == null) {
				if (other.objectKey != null)
					return false;
			} else if (!objectKey.equals(other.objectKey))
				return false;
			if (objectVer == null) {
				if (other.objectVer != null)
					return false;
			} else if (!objectVer.equals(other.objectVer))
				return false;
			return true;
		}

		private S3ArtGenerator getOuterType() {
			return S3ArtGenerator.this;
		}	
	}
	
	private static class S3ObjectData
	{
		private long durationMs;
		private long sizeGB;
		
		private S3ObjectData(long sizeGB)
		{
			this.durationMs = 0l;
			this.sizeGB = sizeGB;
		}
	}
	

}

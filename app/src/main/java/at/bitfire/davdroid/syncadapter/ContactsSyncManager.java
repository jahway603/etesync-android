/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.syncadapter;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.text.TextUtils;

import com.google.common.base.Charsets;
import com.squareup.okhttp.HttpUrl;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import at.bitfire.dav4android.DavAddressBook;
import at.bitfire.dav4android.DavResource;
import at.bitfire.dav4android.exception.DavException;
import at.bitfire.dav4android.exception.HttpException;
import at.bitfire.dav4android.exception.PreconditionFailedException;
import at.bitfire.dav4android.property.AddressData;
import at.bitfire.dav4android.property.GetCTag;
import at.bitfire.dav4android.property.GetContentType;
import at.bitfire.dav4android.property.GetETag;
import at.bitfire.dav4android.property.SupportedAddressData;
import at.bitfire.davdroid.ArrayUtils;
import at.bitfire.davdroid.Constants;
import at.bitfire.davdroid.HttpClient;
import at.bitfire.davdroid.resource.LocalAddressBook;
import at.bitfire.davdroid.resource.LocalContact;
import at.bitfire.vcard4android.Contact;
import at.bitfire.vcard4android.ContactsStorageException;
import ezvcard.VCardVersion;
import ezvcard.util.IOUtils;
import lombok.Cleanup;
import lombok.RequiredArgsConstructor;

public class ContactsSyncManager extends SyncManager {
    protected static final int
            MAX_MULTIGET = 10,
            NOTIFICATION_ID = 1;

    protected HttpUrl addressBookURL;
    protected DavAddressBook davCollection;
    protected boolean hasVCard4;

    protected LocalAddressBook addressBook;
    String currentCTag;

    Map<String, LocalContact> localContacts;
    Map<String, DavResource> remoteContacts;
    Set<DavResource> toDownload;


    public ContactsSyncManager(Context context, Account account, Bundle extras, ContentProviderClient provider, SyncResult result) {
        super(NOTIFICATION_ID, context, account, extras, provider, result);
    }


    @Override
    protected void prepare() {
        addressBookURL = HttpUrl.parse(settings.getAddressBookURL());
        davCollection = new DavAddressBook(httpClient, addressBookURL);

        // prepare local address book
        addressBook = new LocalAddressBook(account, provider);
    }

    @Override
    protected void queryCapabilities() throws DavException, IOException, HttpException {
        // prepare remote address book
        hasVCard4 = false;
        davCollection.propfind(0, SupportedAddressData.NAME, GetCTag.NAME);
        SupportedAddressData supportedAddressData = (SupportedAddressData) davCollection.properties.get(SupportedAddressData.NAME);
        if (supportedAddressData != null)
            for (MediaType type : supportedAddressData.types)
                if ("text/vcard; version=4.0".equalsIgnoreCase(type.toString()))
                    hasVCard4 = true;
        Constants.log.info("Server advertises VCard/4 support: " + hasVCard4);
    }

    @Override
    protected void processLocallyDeleted() throws ContactsStorageException {
        // Remove locally deleted contacts from server (if they have a name, i.e. if they were uploaded before),
        // but only if they don't have changed on the server. Then finally remove them from the local address book.
        LocalContact[] localList = addressBook.getDeleted();
        for (LocalContact local : localList) {
            final String fileName = local.getFileName();
            if (!TextUtils.isEmpty(fileName)) {
                Constants.log.info(fileName + " has been deleted locally -> deleting from server");
                try {
                    new DavResource(httpClient, addressBookURL.newBuilder().addPathSegment(fileName).build())
                            .delete(local.eTag);
                } catch (IOException | HttpException e) {
                    Constants.log.warn("Couldn't delete " + fileName + " from server");
                }
            } else
                Constants.log.info("Removing local contact #" + local.getId() + " which has been deleted locally and was never uploaded");
            local.delete();
            syncResult.stats.numDeletes++;
        }
    }

    @Override
    protected void processLocallyCreated() throws ContactsStorageException {
        // assign file names and UIDs to new contacts so that we can use the file name as an index
        for (LocalContact local : addressBook.getWithoutFileName()) {
            String uuid = UUID.randomUUID().toString();
            Constants.log.info("Found local contact #" + local.getId() + " without file name; assigning name UID/name " + uuid + "[.vcf]");
            local.updateFileNameAndUID(uuid);
        }
    }

    @Override
    protected void uploadDirty() throws ContactsStorageException, IOException, HttpException {
        // upload dirty contacts
        for (LocalContact local : addressBook.getDirty()) {
            final String fileName = local.getFileName();

            DavResource remote = new DavResource(httpClient, addressBookURL.newBuilder().addPathSegment(fileName).build());

            RequestBody vCard = RequestBody.create(
                    hasVCard4 ? DavAddressBook.MIME_VCARD4 : DavAddressBook.MIME_VCARD3_UTF8,
                    local.getContact().toStream(hasVCard4 ? VCardVersion.V4_0 : VCardVersion.V3_0).toByteArray()
            );

            try {
                if (local.eTag == null) {
                    Constants.log.info("Uploading new contact " + fileName);
                    remote.put(vCard, null, true);
                    // TODO handle 30x
                } else {
                    Constants.log.info("Uploading locally modified contact " + fileName);
                    remote.put(vCard, local.eTag, false);
                    // TODO handle 30x
                }

            } catch (PreconditionFailedException e) {
                Constants.log.info("Contact has been modified on the server before upload, ignoring", e);
            }

            String eTag = null;
            GetETag newETag = (GetETag) remote.properties.get(GetETag.NAME);
            if (newETag != null) {
                eTag = newETag.eTag;
                Constants.log.debug("Received new ETag=" + eTag + " after uploading");
            } else
                Constants.log.debug("Didn't receive new ETag after uploading, setting to null");

            local.clearDirty(eTag);
        }
    }

    @Override
    protected boolean checkSyncState() throws ContactsStorageException {
        // check CTag (ignore on manual sync)
        currentCTag = null;
        GetCTag getCTag = (GetCTag) davCollection.properties.get(GetCTag.NAME);
        if (getCTag != null)
            currentCTag = getCTag.cTag;

        String localCTag = null;
        if (extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL))
            Constants.log.info("Manual sync, ignoring CTag");
        else
            localCTag = addressBook.getCTag();

        if (currentCTag != null && currentCTag.equals(localCTag)) {
            Constants.log.info("Remote address book didn't change (CTag=" + currentCTag + "), no need to list VCards");
            return false;
        } else
            return true;
    }

    @Override
    protected void listLocal() throws ContactsStorageException {
        // fetch list of local contacts and build hash table to index file name
        LocalContact[] localList = addressBook.getAll();
        localContacts = new HashMap<>(localList.length);
        for (LocalContact contact : localList) {
            Constants.log.debug("Found local contact: " + contact.getFileName());
            localContacts.put(contact.getFileName(), contact);
        }
    }

    @Override
    protected void listRemote() throws IOException, HttpException, DavException, ContactsStorageException {
        // fetch list of remote VCards and build hash table to index file name
        Constants.log.info("Listing remote VCards");
        davCollection.queryMemberETags();
        remoteContacts = new HashMap<>(davCollection.members.size());
        for (DavResource vCard : davCollection.members) {
            String fileName = vCard.fileName();
            Constants.log.debug("Found remote VCard: " + fileName);
            remoteContacts.put(fileName, vCard);
        }
    }

    @Override
    protected void compareEntries() throws IOException, HttpException, DavException, ContactsStorageException {
        /* check which contacts
           1. are not present anymore remotely -> delete immediately on local side
           2. updated remotely -> add to downloadNames
           3. added remotely  -> add to downloadNames
         */
        toDownload = new HashSet<>();
        for (String localName : localContacts.keySet()) {
            DavResource remote = remoteContacts.get(localName);
            if (remote == null) {
                Constants.log.info(localName + " is not on server anymore, deleting");
                localContacts.get(localName).delete();
                syncResult.stats.numDeletes++;
            } else {
                // contact is still on server, check whether it has been updated remotely
                GetETag getETag = (GetETag) remote.properties.get(GetETag.NAME);
                if (getETag == null || getETag.eTag == null)
                    throw new DavException("Server didn't provide ETag");
                String localETag = localContacts.get(localName).eTag,
                        remoteETag = getETag.eTag;
                if (remoteETag.equals(localETag))
                    syncResult.stats.numSkippedEntries++;
                else {
                    Constants.log.info(localName + " has been changed on server (current ETag=" + remoteETag + ", last known ETag=" + localETag + ")");
                    toDownload.add(remote);
                }

                // remote entry has been seen, remove from list
                remoteContacts.remove(localName);
            }
        }

        // add all unseen (= remotely added) remote contacts
        if (!remoteContacts.isEmpty()) {
            Constants.log.info("New VCards have been found on the server: " + TextUtils.join(", ", remoteContacts.keySet()));
            toDownload.addAll(remoteContacts.values());
        }
    }

    @Override
    protected void downloadRemote() throws IOException, HttpException, DavException, ContactsStorageException {
        Constants.log.info("Downloading " + toDownload.size() + " contacts (" + MAX_MULTIGET + " at once)");

        // prepare downloader which may be used to download external resource like contact photos
        Contact.Downloader downloader = new ResourceDownloader(httpClient, addressBookURL);

        // download new/updated VCards from server
        for (DavResource[] bunch : ArrayUtils.partition(toDownload.toArray(new DavResource[toDownload.size()]), MAX_MULTIGET)) {
            Constants.log.info("Downloading " + TextUtils.join(" + ", bunch));

            if (bunch.length == 1) {
                // only one contact, use GET
                DavResource remote = bunch[0];

                ResponseBody body = remote.get("text/vcard;q=0.5, text/vcard;charset=utf-8;q=0.8, text/vcard;version=4.0");
                String eTag = ((GetETag) remote.properties.get(GetETag.NAME)).eTag;

                @Cleanup InputStream stream = body.byteStream();
                processVCard(syncResult, addressBook, localContacts, remote.fileName(), eTag, stream, body.contentType().charset(Charsets.UTF_8), downloader);

            } else {
                // multiple contacts, use multi-get
                List<HttpUrl> urls = new LinkedList<>();
                for (DavResource remote : bunch)
                    urls.add(remote.location);
                davCollection.multiget(urls.toArray(new HttpUrl[urls.size()]), hasVCard4);

                // process multiget results
                for (DavResource remote : davCollection.members) {
                    String eTag;
                    GetETag getETag = (GetETag) remote.properties.get(GetETag.NAME);
                    if (getETag != null)
                        eTag = getETag.eTag;
                    else
                        throw new DavException("Received multi-get response without ETag");

                    Charset charset = Charsets.UTF_8;
                    GetContentType getContentType = (GetContentType) remote.properties.get(GetContentType.NAME);
                    if (getContentType != null && getContentType.type != null) {
                        MediaType type = MediaType.parse(getContentType.type);
                        if (type != null)
                            charset = type.charset(Charsets.UTF_8);
                    }

                    AddressData addressData = (AddressData) remote.properties.get(AddressData.NAME);
                    if (addressData == null || addressData.vCard == null)
                        throw new DavException("Received multi-get response without address data");

                    @Cleanup InputStream stream = new ByteArrayInputStream(addressData.vCard.getBytes());
                    processVCard(syncResult, addressBook, localContacts, remote.fileName(), eTag, stream, charset, downloader);
                }
            }
        }
    }

    @Override
    protected void saveSyncState() throws ContactsStorageException {
        /* Save sync state (CTag). It doesn't matter if it has changed during the sync process
           (for instance, because another client has uploaded changes), because this will simply
           cause all remote entries to be listed at the next sync. */
        Constants.log.info("Saving sync state: CTag=" + currentCTag);
        addressBook.setCTag(currentCTag);
    }

    private void processVCard(SyncResult syncResult, LocalAddressBook addressBook, Map<String, LocalContact>localContacts, String fileName, String eTag, InputStream stream, Charset charset, Contact.Downloader downloader) throws IOException, ContactsStorageException {
        Contact contacts[] = Contact.fromStream(stream, charset, downloader);
        if (contacts.length == 1) {
            Contact newData = contacts[0];

            // delete local contact, if it exists
            LocalContact localContact = localContacts.get(fileName);
            if (localContact != null) {
                Constants.log.info("Updating " + fileName + " in local address book");
                localContact.eTag = eTag;
                localContact.update(newData);
                syncResult.stats.numUpdates++;
            } else {
                Constants.log.info("Adding " + fileName + " to local address book");
                localContact = new LocalContact(addressBook, newData, fileName, eTag);
                localContact.add();
                syncResult.stats.numInserts++;
            }
        } else
            Constants.log.error("Received VCard with not exactly one VCARD, ignoring " + fileName);
    }


    @RequiredArgsConstructor
    static class ResourceDownloader implements Contact.Downloader {
        final HttpClient httpClient;
        final HttpUrl baseUrl;

        @Override
        public byte[] download(String url, String accepts) {
            HttpUrl httpUrl = HttpUrl.parse(url);
            HttpClient resourceClient = new HttpClient(httpClient, httpUrl.host());
            try {
                Response response = resourceClient.newCall(new Request.Builder()
                        .get()
                        .url(httpUrl)
                        .build()).execute();

                ResponseBody body = response.body();
                if (body != null) {
                    @Cleanup InputStream stream = body.byteStream();
                    if (response.isSuccessful() && stream != null) {
                        return IOUtils.toByteArray(stream);
                    } else
                        Constants.log.error("Couldn't download external resource");
                }
            } catch(IOException e) {
                Constants.log.error("Couldn't download external resource", e);
            }
            return null;
        }
    }

}
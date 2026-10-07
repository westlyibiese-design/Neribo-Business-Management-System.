insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('nbms-media','nbms-media',true,8388608,array['image/jpeg','image/png','image/webp','image/gif','image/avif'])
on conflict (id) do update set public=excluded.public, file_size_limit=excluded.file_size_limit, allowed_mime_types=excluded.allowed_mime_types;
drop policy if exists "nbms-media public read" on storage.objects;
create policy "nbms-media public read" on storage.objects for select to public using (bucket_id = 'nbms-media');
